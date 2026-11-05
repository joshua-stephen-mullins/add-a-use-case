package team.projectpulse.student;

import org.hamcrest.Matchers;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.instructor.InstructorRepository;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.StatusCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-STU-deactivate-student through {@code POST /students/{studentId}/deactivation}: the main flow for a
 * section instructor and for a course admin who is not assigned to the section, 3a (already deactivated), and
 * 3b (anyone else, an id that names no student, and no token). FR-SEC-active-account, what happens to the
 * deactivated student's own token, is {@code ActiveAccountIntegrationTest}.
 */
@DisplayName("Integration tests for UC-STU-deactivate-student")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    private static final int NO_SUCH_ID = 999999;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StudentRepository studentRepository;

    @Autowired
    InstructorRepository instructorRepository;

    @Autowired
    SectionRepository sectionRepository;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String adminBingyangToken; // Course admin of course 1, also an instructor of section 2

    String instructorBillToken; // Instructor of section 2, not a course admin

    String adminTimToken; // Course admin of another course

    String studentJohnToken;

    Student john; // Section 2, Team1

    Student eric; // Section 2, Team1, John's teammate


    @BeforeEach
    void setUp() throws Exception {
        this.adminBingyangToken = login("b.wei@abc.edu");
        this.instructorBillToken = login("b.gates@abc.edu");
        this.adminTimToken = login("t.cook@abc.edu");
        this.studentJohnToken = login("j.smith@abc.edu");
        this.john = this.studentRepository.findAll().stream().filter(s -> s.getEmail().equals("j.smith@abc.edu")).findFirst().orElseThrow();
        this.eric = this.studentRepository.findAll().stream().filter(s -> s.getEmail().equals("e.hudson@abc.edu")).findFirst().orElseThrow();
    }

    private String login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        JSONObject json = new JSONObject(mvcResult.getResponse().getContentAsString());
        return "Bearer " + json.getJSONObject("data").getString("token");
    }

    private String deactivationUrl(int studentId) {
        return this.baseUrl + "/students/" + studentId + "/deactivation";
    }

    @Test
    @DisplayName("Main flow: a section instructor deactivates a student; her team and past work are kept")
    void sectionInstructorDeactivatesStudent() throws Exception {
        Integer teamIdBefore = this.john.getTeam().getTeamId();

        this.mockMvc.perform(post(deactivationUrl(this.john.getId())).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.message").value("Deactivate student successfully"))
                .andExpect(jsonPath("$.data.id").value(this.john.getId()))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.teamId").value(teamIdBefore));

        Student deactivatedJohn = this.studentRepository.findById(this.john.getId()).orElseThrow();
        assertThat(deactivatedJohn.isEnabled()).isFalse();
        assertThat(deactivatedJohn.getTeam().getTeamId()).isEqualTo(teamIdBefore);

        // His past weekly activity reports are still there for his instructor
        this.mockMvc.perform(post(this.baseUrl + "/activities/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + this.john.getId() + "\",\"startWeek\":\"2023-W31\",\"endWeek\":\"2023-W33\"}")
                        .param("page", "0").param("size", "50")
                        .accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.content", Matchers.not(Matchers.empty())))
                .andExpect(jsonPath("$.data.content[*].studentId", Matchers.everyItem(Matchers.is(this.john.getId()))));

        // And so are the peer evaluations he received
        this.mockMvc.perform(get(this.baseUrl + "/evaluations/students/" + this.john.getId() + "/week/2023-W31/details").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data", Matchers.not(Matchers.empty())));
    }

    @Test
    @DisplayName("Main flow: the course admin may deactivate even when not assigned to the student's section")
    void courseAdminNotAssignedToSectionDeactivatesStudent() throws Exception {
        // Bingyang is seeded as both the course admin and an instructor of section 2; take him off the section so
        // only BR-student-lifecycle's course-admin clause can admit him
        Instructor bingyang = this.instructorRepository.findAll().stream().filter(i -> i.getEmail().equals("b.wei@abc.edu")).findFirst().orElseThrow();
        Section section2 = this.sectionRepository.findById(this.john.getSection().getSectionId()).orElseThrow();
        section2.removeInstructor(bingyang);
        this.sectionRepository.save(section2);
        assertThat(section2.getInstructors()).noneMatch(i -> i.getId().equals(bingyang.getId()));

        this.mockMvc.perform(post(deactivationUrl(this.john.getId())).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.adminBingyangToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.message").value("Deactivate student successfully"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @DisplayName("3a: a second deactivation is rejected and changes nothing")
    void secondDeactivationIsRejected() throws Exception {
        this.mockMvc.perform(post(deactivationUrl(this.john.getId())).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isOk());

        this.mockMvc.perform(post(deactivationUrl(this.john.getId())).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.INVALID_ARGUMENT))
                .andExpect(jsonPath("$.message").value("This student is already deactivated."));

        Student john = this.studentRepository.findById(this.john.getId()).orElseThrow();
        assertThat(john.isEnabled()).isFalse();
        assertThat(john.getTeam()).isNotNull();
    }

    @Test
    @DisplayName("3b: the admin of another course is forbidden, without detail, and the student stays active")
    void adminOfAnotherCourseIsForbidden() throws Exception {
        expectForbidden(this.john.getId(), this.adminTimToken);
        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("3b: a student cannot deactivate herself")
    void studentCannotDeactivateHerself() throws Exception {
        expectForbidden(this.john.getId(), this.studentJohnToken);
        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("3b: a student cannot deactivate a teammate")
    void studentCannotDeactivateTeammate() throws Exception {
        expectForbidden(this.eric.getId(), this.studentJohnToken);
        assertThat(this.studentRepository.findById(this.eric.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("3b: an id that names no student gets the same 403 as a forbidden one")
    void unknownStudentIdIsForbiddenNotNotFound() throws Exception {
        expectForbidden(NO_SUCH_ID, this.instructorBillToken);
    }

    @Test
    @DisplayName("3b: no token is 401, as on every route")
    void noTokenIsUnauthorized() throws Exception {
        this.mockMvc.perform(post(deactivationUrl(this.john.getId())).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isTrue();
    }

    private void expectForbidden(int studentId, String token) throws Exception {
        this.mockMvc.perform(post(deactivationUrl(studentId)).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN))
                .andExpect(jsonPath("$.message").value("No permission."))
                .andExpect(jsonPath("$.data").value("Access Denied"));
    }

}
