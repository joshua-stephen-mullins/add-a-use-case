package team.projectpulse.student;

import org.junit.jupiter.api.AfterEach;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.team.Team;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    StudentRepository studentRepository;
    @Mock
    SectionRepository sectionRepository;
    @Mock
    UserInvitationService userInvitationService;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    UserUtils userUtils;

    @InjectMocks
    private StudentService studentService;

    List<Student> students;

    @BeforeEach
    void setUp() {
        // Create students
        Student john = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
        Student eric = new Student("eric", "Eric", "Hudson", "e.hudson@abc.edu", "123456", true, "student");
        Student jerry = new Student("jerry", "Jerry", "Moon", "j.moon@abc.edu", "123456", true, "student");
        Student woody = new Student("woody", "Woody", "Allen", "w.allen@abc.edu", "123456", true, "student");
        Student amanda = new Student("amanda", "Amanda", "Wagner", "a.wagner@abc.edu", "123456", true, "student");
        Student cora = new Student("cora", "Cora", "Manning", "c.manning@abc.edu", "123456", true, "student");
        Student agustin = new Student("agustin", "Agustin", "Freeman", "a.freeman@abc.edu", "123456", true, "student");
        Student mavis = new Student("mavis", "Mavis", "Huber", "m.huber@abc.edu", "123456", true, "student");
        Student mary = new Student("mary", "Mary", "Vargas", "m.vargas@abc.edu", "123456", true, "student");
        Student rosendo = new Student("rosendo", "Rosendo", "Maxwell", "r.maxwell@abc.edu", "123456", true, "student");

        this.students = List.of(john, eric, jerry, woody, amanda, cora, agustin, mavis, mary, rosendo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // Clear the security context after each test to avoid interference between tests
    }

    @Test
    void testFindByCriteria() {
        // Given
        Map<String, String> searchCriteria = Map.of("firstName", "john");
        PageImpl expectedPage = new PageImpl(List.of(this.students.get(0)));

        given(this.userUtils.getUserSectionId()).willReturn(2);
        given(this.studentRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(expectedPage);
        // When
        Page<Student> result = this.studentService.findByCriteria(searchCriteria, PageRequest.of(0, 20));

        // Then
        assertThat(result.getContent().size()).isEqualTo(1);
    }

    @Test
    void testFindStudentById() {
        // Given
        Student john = this.students.get(0);
        given(this.studentRepository.findById(1)).willReturn(Optional.of(john));

        // When
        Student result = this.studentService.findStudentById(1);

        // Then
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");
    }

    @Test
    void testSaveStudent() {
        // Given
        Student jan = new Student("jan", "Jan", "Mckinney", "j.mckinney@abc.edu", "Abc123456", true, "student");
        doNothing().when(this.userInvitationService).validateUserInvitation(anyString(), anyString(), anyInt(), anyInt(), anyString());
        given(this.sectionRepository.findById(1)).willReturn(Optional.of(new Section()));
        given(this.passwordEncoder.encode(anyString())).willReturn("Encoded Password");
        given(this.studentRepository.save(jan)).willReturn(jan);

        // When
        Student result = this.studentService.saveStudent(jan, 1, 1, "token", "student");

        // Then
        assertThat(result.getFirstName()).isEqualTo("Jan");
        assertThat(result.getLastName()).isEqualTo("Mckinney");
        assertThat(result.getEmail()).isEqualTo("j.mckinney@abc.edu");
        assertThat(result.getRoles()).isEqualTo("student");
        assertThat(result.getSection()).isNotNull();
        assertThat(result.isEnabled()).isEqualTo(true);
    }

    @Test
    void testStudentUpdateOwnInfo() {
        // Given
        Student update = new Student("john", "John (updated)", "Smith (updated)", "j.smith@abc.edu (updated)", "", false, "admin student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        given(this.studentRepository.save(any(Student.class))).willReturn(this.students.get(0));
        // A student has no ROLE_instructor; the unstubbed mock answers false

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.getUsername()).isEqualTo(update.getUsername());
        assertThat(result.getFirstName()).isEqualTo(update.getFirstName());
        assertThat(result.getLastName()).isEqualTo(update.getLastName());
        assertThat(result.getEmail()).isEqualTo(update.getEmail());
        assertThat(result.getRoles()).isNotEqualTo(update.getRoles());
        assertThat(result.isEnabled()).isNotEqualTo(update.isEnabled());
    }

    /**
     * UC-STU-deactivate-student, trunk: editing a student never deactivates her, so the warning and confirmation
     * of the deactivate action cannot be bypassed through the edit form or a direct PUT.
     */
    @Test
    void testInstructorUpdateDoesNotLowerEnabled() {
        // Given
        Student update = new Student("john", "John (updated)", "Smith (updated)", "j.smith@abc.edu (updated)", "", false, "admin student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        given(this.studentRepository.save(any(Student.class))).willReturn(this.students.get(0));

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.getUsername()).isEqualTo(update.getUsername());
        assertThat(result.getFirstName()).isEqualTo(update.getFirstName());
        assertThat(result.getLastName()).isEqualTo(update.getLastName());
        assertThat(result.getEmail()).isEqualTo(update.getEmail());
        assertThat(result.isEnabled()).isTrue(); // enabled: false in the update is ignored, the student stays active
    }

    /**
     * The interim reactivation path until UC-STU-reactivate-student is built: an instructor's update with
     * enabled: true raises the flag of a deactivated student.
     */
    @Test
    void testInstructorUpdateRaisesEnabledOfADeactivatedStudent() {
        // Given
        Student deactivatedJohn = this.students.get(0);
        deactivatedJohn.setEnabled(false);
        Student update = new Student("john", "John", "Smith", "j.smith@abc.edu", "", true, "student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(deactivatedJohn));
        given(this.studentRepository.save(any(Student.class))).willReturn(deactivatedJohn);
        given(this.userUtils.hasRole("ROLE_instructor")).willReturn(true);

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.isEnabled()).isTrue();
    }

    /**
     * Raising the flag is an instructor's path only: a caller without ROLE_instructor sending enabled: true for a
     * deactivated student leaves her deactivated.
     */
    @Test
    void testStudentUpdateDoesNotRaiseEnabled() {
        // Given
        Student deactivatedJohn = this.students.get(0);
        deactivatedJohn.setEnabled(false);
        Student update = new Student("john", "John", "Smith", "j.smith@abc.edu", "", true, "student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(deactivatedJohn));
        given(this.studentRepository.save(any(Student.class))).willReturn(deactivatedJohn);
        given(this.userUtils.hasRole("ROLE_instructor")).willReturn(false);

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.isEnabled()).isFalse();
    }

    /**
     * UC-STU-deactivate-student main flow, step 6: only enabled changes; the team membership stays
     * (BR-deactivated-student-standing).
     */
    @Test
    void testDeactivateStudent() {
        // Given
        Student john = this.students.get(0);
        Team team1 = new Team("Team1", "Team 1 description", "https://www.team1.com");
        team1.setTeamId(1);
        john.setTeam(team1);
        given(this.studentRepository.findById(1)).willReturn(Optional.of(john));
        given(this.studentRepository.save(john)).willReturn(john);

        // When
        Student result = this.studentService.deactivateStudent(1);

        // Then
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getTeam()).isSameAs(team1);
        verify(this.studentRepository, times(1)).save(john);
    }

    /**
     * UC-STU-deactivate-student 3a: a second deactivation is rejected and changes nothing.
     */
    @Test
    void testDeactivateStudentAlreadyDeactivated() {
        // Given
        Student john = this.students.get(0);
        john.setEnabled(false);
        given(this.studentRepository.findById(1)).willReturn(Optional.of(john));

        // When
        Throwable throwable = catchThrowable(() -> this.studentService.deactivateStudent(1));

        // Then
        assertThat(throwable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This student is already deactivated.");
        verify(this.studentRepository, never()).save(any(Student.class));
    }

    @Test
    void testDeactivateStudentNotFound() {
        // Given
        given(this.studentRepository.findById(999)).willReturn(Optional.empty());

        // When
        Throwable throwable = catchThrowable(() -> this.studentService.deactivateStudent(999));

        // Then
        assertThat(throwable).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void testDeleteStudent() {
        // Given
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        doNothing().when(this.studentRepository).deleteById(1);

        // When
        this.studentService.deleteStudent(1);

        // Then
        verify(this.studentRepository, times(1)).deleteById(1);
    }

}