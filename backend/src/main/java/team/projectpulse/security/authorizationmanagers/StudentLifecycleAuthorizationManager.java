package team.projectpulse.security.authorizationmanagers;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import team.projectpulse.student.StudentSecurityService;

import java.util.function.Supplier;

/**
 * Guards the routes that change a student's standing (BR-student-lifecycle): an instructor assigned to the
 * student's course section, or the admin of the student's course, may pass; nobody else, the student herself
 * included.
 *
 * <p>{@code UserOwnershipAuthorizationManager} is not reused because it admits the student herself and does not
 * admit a course admin who is not assigned to the section. Reads {@code studentId} and composes
 * {@link StudentSecurityService#isCurrentUserInstructorOfStudentSection} with
 * {@link StudentSecurityService#isCurrentUserAdminOfStudentCourse}. An id that is missing, not a number, or
 * names no student is denied, so an outsider probing ids learns nothing (CO-ferpa).
 */
@Component
public class StudentLifecycleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final StudentSecurityService studentSecurityService;


    public StudentLifecycleAuthorizationManager(StudentSecurityService studentSecurityService) {
        this.studentSecurityService = studentSecurityService;
    }

    @Override
    public @Nullable AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication, RequestAuthorizationContext context) {
        Integer studentId = PathVariables.readId(context, "studentId");
        if (studentId == null) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(
                this.studentSecurityService.isCurrentUserInstructorOfStudentSection(studentId)
                        || this.studentSecurityService.isCurrentUserAdminOfStudentCourse(studentId));
    }

}
