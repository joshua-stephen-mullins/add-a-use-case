package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.student.Student;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.MyUserPrincipal;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEC-active-account: a deactivated account is denied on its next request, not only at its next login.
 *
 * <p>Each test logs a user in while the account is active, so the token is signed and unexpired, then changes
 * the account under that token and shows the resource server refuses it with the same {@code 401}
 * "User account is abnormal." envelope a deactivated login gets. The account is flipped through the
 * repository here because this is the trunk: the deactivation route of UC-STU-deactivate-student is a leaf
 * built on top of it, and its own test drives the same check through {@code POST /students/{id}/deactivation}.
 */
@DisplayName("Integration tests for FR-SEC-active-account, the per-request active-account check")
class ActiveAccountIntegrationTest extends AbstractIntegrationTest {

    private static final String JOHN = "j.smith@abc.edu";
    private static final String BILL = "b.gates@abc.edu";
    private static final int NO_SUCH_USER_ID = 999999;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtProvider jwtProvider;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    PeerEvaluationUser john;

    PeerEvaluationUser bill;


    @BeforeEach
    void setUp() {
        this.john = this.userRepository.findByEmail(JOHN).orElseThrow();
        this.bill = this.userRepository.findByEmail(BILL).orElseThrow();
    }

    private String login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        JSONObject json = new JSONObject(mvcResult.getResponse().getContentAsString());
        return "Bearer " + json.getJSONObject("data").getString("token");
    }

    @Test
    @DisplayName("A deactivated student's next request with her still-valid token is refused")
    void deactivatedStudentIsRefusedOnHerNextRequest() throws Exception {
        String johnToken = login(JOHN);

        // The token works while the account is active
        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, johnToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true));

        this.john.setEnabled(false);
        this.userRepository.save(this.john);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, johnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));
    }

    @Test
    @DisplayName("A deactivated instructor's next request with his still-valid token is refused")
    void deactivatedInstructorIsRefusedOnHisNextRequest() throws Exception {
        String billToken = login(BILL);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, billToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true));

        this.bill.setEnabled(false);
        this.userRepository.save(this.bill);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, billToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));
    }

    @Test
    @DisplayName("A token whose user row no longer exists is refused")
    void tokenOfADeletedUserIsRefused() throws Exception {
        // A validly signed token issued for a user id that names no row, which is what a token looks like once
        // its user has been deleted after login
        Student deleted = new Student("deleted", "Deleted", "User", "deleted@abc.edu", "123456", true, "student");
        deleted.setId(NO_SUCH_USER_ID);
        MyUserPrincipal principal = new MyUserPrincipal(deleted);
        String token = "Bearer " + this.jwtProvider.createToken(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        this.mockMvc.perform(get(this.baseUrl + "/students/" + NO_SUCH_USER_ID).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));
    }

    @Test
    @DisplayName("A deactivated student cannot log in, even with the right password")
    void deactivatedStudentCannotLogIn() throws Exception {
        this.john.setEnabled(false);
        this.userRepository.save(this.john);

        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(JOHN, "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));
    }

    @Test
    @DisplayName("An active account's token keeps working, so the check costs nothing but one read")
    void activeAccountIsStillAdmitted() throws Exception {
        String billToken = login(BILL);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.john.getId()).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, billToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.data.email").value(JOHN));
    }

}
