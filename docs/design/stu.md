# Student (STU) Design

> Realizes: UC-STU-deactivate-student
> Depends on: BR-student-lifecycle (who may deactivate), BR-deactivated-student-standing (what deactivation leaves in place), BR-submission-owed, FR-SEC-active-account, CO-ferpa
> See: [use cases](../requirements/use-cases.md#student), [business rules](../requirements/business-rules.md), [architectural-design.md](architectural-design.md#shared-foundation-components) (shared-foundation component view, Crosscutting Concepts / Security & Compliance)
> Designed against `main` at `b9d21b0` (2026-11-05).

## Overview

The STU area is the `student` bounded context of the shared foundation: the student entity, its search, registration, update, and delete. This first design-of-record adds one thing to it, a dedicated **deactivate** action, and fixes what "deactivated" means across the product. Before this design, deactivation was a Status switch in the student edit form with no warning (`StudentService.updateStudent` set `enabled` along with the name and email), only login checked the flag, and the three places that read it disagreed: `SubmissionStatusService.findOwedItems` skipped deactivated students, `EvaluationService.generateWeeklyPeerEvaluationReportForSection` listed them forever, and `EvaluationService.addPeerEvaluation` let teammates keep evaluating them.

The use case touches three slices. The `student` slice owns the action and its route. The `security` slice owns who may call it and the per-request active-account check. The `evaluation` slice adopts BR-deactivated-student-standing in its two methods. Nothing moves between components; the architecture-of-record is unchanged.

## Components & classes

```mermaid
classDiagram
    class StudentController {
        +deactivateStudent(studentId) Result
    }
    class StudentService {
        +deactivateStudent(studentId) Student
        +updateStudent(studentId, update) Student
    }
    class StudentSecurityService {
        +isCurrentUserInstructorOfStudentSection(studentId) boolean
        +isCurrentUserAdminOfStudentCourse(studentId) boolean
    }
    class StudentLifecycleAuthorizationManager {
        +authorize(auth, context) AuthorizationResult
    }
    class ActiveAccountJwtAuthenticationConverter {
        +convert(jwt) AbstractAuthenticationToken
    }
    class EvaluationService {
        +addPeerEvaluation(evaluation) PeerEvaluation
        +generateWeeklyPeerEvaluationReportForSection(sectionId, week)
    }
    StudentController --> StudentService
    StudentLifecycleAuthorizationManager --> StudentSecurityService
    ActiveAccountJwtAuthenticationConverter --> UserRepository
    StudentService --> StudentRepository
    EvaluationService --> StudentRepository
```

| Component | Files | Status |
|---|---|---|
| Route + action | `backend/.../student/StudentController.java` (`deactivateStudent`), `StudentService.java` (`deactivateStudent`; `updateStudent` no longer lowers `enabled`) | changed |
| Who may | `backend/.../student/StudentSecurityService.java` (`isCurrentUserAdminOfStudentCourse`), `backend/.../security/authorizationmanagers/StudentLifecycleAuthorizationManager.java`, `SecurityConfiguration.java` (one route rule) | new / changed |
| Active account per request | `backend/.../security/ActiveAccountJwtAuthenticationConverter.java`, registered as the resource server's JWT converter in `SecurityConfiguration.java` | new |
| Deactivated student in peer evaluations | `backend/.../evaluation/EvaluationService.java` (`addPeerEvaluation`, `generateWeeklyPeerEvaluationReportForSection`) | changed |
| UI | `frontend/src/pages/students/Students.vue` (Deactivate button + confirmation), `EditStudentForm.vue` (Status switch removed), `frontend/src/apis/student/index.ts` (`deactivateStudent`), the peer evaluation form (lists only active teammates) | changed |
| Reused as is | `Result` envelope, `ExceptionHandlerAdvice`, `StudentToStudentDtoConverter`, `UserRepository`, `ElMessageBox` | reused |

## Sequence

### UC-STU-deactivate-student: main success scenario and extensions

```mermaid
sequenceDiagram
    actor I as Course admin / instructor
    participant SPA as SPA (Students.vue)
    participant AM as StudentLifecycleAuthorizationManager
    participant SC as StudentController
    participant SS as StudentService
    participant DB as StudentRepository
    I->>SPA: Deactivate on a student's row (steps 1-3)
    SPA->>I: confirmation dialog listing the consequences (step 4)
    alt cancels (5a)
        I->>SPA: Cancel
        SPA-->>I: dialog closes, nothing sent, list unchanged
    else confirms (step 5)
        I->>SPA: Confirm
        SPA->>AM: POST /students/{id}/deactivation
        alt not a section instructor nor the course admin (3b)
            AM-->>SPA: 403 Result "No permission."
        else permitted
            AM->>SC: pass
            SC->>SS: deactivateStudent(id)
            SS->>DB: findById(id)
            alt unknown id
                SS-->>SPA: 404 Result
            else already deactivated (3a)
                SS-->>SPA: 400 Result "already deactivated"
            else active
                SS->>DB: save(enabled = false) (step 6)
                SS-->>SC: student
                SC-->>SPA: 200 Result, student DTO
                SPA-->>I: "deactivated" message, list reloads (step 6)
            end
        end
    end
```

### FR-SEC-active-account: the deactivated student's next request

```mermaid
sequenceDiagram
    actor S as Deactivated student (token still valid)
    participant RS as Resource server (JWT filter)
    participant C as ActiveAccountJwtAuthenticationConverter
    participant UR as UserRepository
    S->>RS: any request with the old bearer token
    RS->>C: convert(jwt)
    C->>UR: findById(userId claim)
    C-->>RS: DisabledException (enabled is false)
    RS-->>S: 401 Result "User account is abnormal."
```

## API contract

| Endpoint or job | Caller (who may) | Request | Success | Errors, by extension |
|---|---|---|---|---|
| `POST /api/v1/students/{studentId}/deactivation` | An instructor assigned to the student's course section, or the course admin of her course (BR-student-lifecycle): `StudentLifecycleAuthorizationManager`; route rule added with the other `/students/**` rules, before the `denyAll()` catch-all | No body | `200`: the student DTO with `enabled: false`. Team, section, activities, and evaluations unchanged | 3b, anyone else including the student herself: `403` `FORBIDDEN` "No permission.", no detail. 3a, already deactivated: `400` `INVALID_ARGUMENT`, message "This student is already deactivated." Unknown id: `404` `NOT_FOUND` |
| `PUT /api/v1/students/{studentId}` (existing) | unchanged | `enabled` in the body is honored only when it raises the flag | unchanged | unchanged |
| Every authenticated route | unchanged | — | unchanged | A bearer token whose user is deactivated: `401` `UNAUTHORIZED` "User account is abnormal.", the same envelope a deactivated login gets today |
| `POST /api/v1/evaluations` (existing) | unchanged | — | unchanged | Evaluatee deactivated: `400` `INVALID_ARGUMENT`, message "You cannot evaluate a deactivated teammate." (BR-deactivated-student-standing) |

## Key decisions

**A dedicated action, not the edit form.** Deactivation is its own `POST` with its own route rule, and `updateStudent` can no longer lower `enabled`, so the warning and confirmation of step 4 cannot be bypassed (Associated Information). `updateStudent` may still raise the flag: that is the only reactivation path until UC-STU-reactivate-student is built, and the student edit form keeps no control for it, so only a direct API call can use it. Rejected: ignoring `enabled` in `updateStudent` entirely, which leaves a deactivated student with no way back. Rejected: keeping the Status switch as a second path, which skips the warning and the section-instructor check.

**POST, not PUT or DELETE.** The action is not idempotent (3a rejects the second call) and deletes nothing, which is the distinction the use case draws against UC-STU-delete-student. Rejected: `PUT /students/{id}/enabled` with a boolean, which would be both deactivate and reactivate on one route with one rule, when the two use cases may yet diverge (reactivation notifies the student; deactivation does not).

**Who may: a new manager, not `UserOwnershipAuthorizationManager`.** The existing manager for `/students/{studentId}` admits the student herself and the section's instructors, and not a course admin who is not assigned to the section. BR-student-lifecycle names the section instructor and the course admin and nobody else, so `StudentLifecycleAuthorizationManager` composes `isCurrentUserInstructorOfStudentSection` with a new `isCurrentUserAdminOfStudentCourse` (the course's `courseAdmin`, resolved through the student's section). It fails closed on an unknown id, as the rest of `StudentSecurityService` does, so an outsider probing ids learns nothing (3b, CO-ferpa). Rejected: `hasAuthority("ROLE_instructor")`, the rule `updateStudent` effectively had, which lets any instructor deactivate any student.

**Active account is checked on every request.** FR-SEC-active-account denies access "regardless of otherwise-valid credentials", but the resource server validated only the token's signature and expiry, so a deactivated student kept access for up to two hours. `ActiveAccountJwtAuthenticationConverter` wraps the existing `JwtAuthenticationConverter`, loads the user by the token's `userId` claim, and throws `DisabledException` when the account is disabled; the existing entry point and `ExceptionHandlerAdvice` turn that into the same `401` a disabled login gets. Cost: one primary-key read per authenticated request, which the user table serves from cache at course scale. This is the trunk commit: it changes what every authenticated request does. Rejected: accepting the token window and documenting it, which makes the FR false for two hours. Rejected: shortening the token lifetime, which inconveniences everyone and still leaves a window. Rejected: a token denylist, which adds state for a case one read answers.

**Deactivation changes standing, not team membership.** `deactivateStudent` touches only `enabled` (BR-deactivated-student-standing). `findOwedItems` already excludes her from what teammates owe, so nothing else in the notification slice changes. Rejected: calling `Team.removeStudent`, which empties her team name on every past report and forces a manual reassignment on reactivation.

**Peer evaluations of a deactivated student are refused, and the report shows her only for weeks on record.** `addPeerEvaluation` rejects an inactive evaluatee, so the three readers of `enabled` now agree with BR-submission-owed: not owed means not accepted. The section report includes a student when she is active or when at least one evaluation of her exists for that week, which keeps her past weeks visible and drops the empty rows after her deactivation without storing a deactivation date. Accepted consequence: a teammate who has not yet evaluated her for her last active week when she is deactivated cannot do so afterwards. Rejected: a `deactivatedAt` column, which adds a migration to answer a question the evaluations already answer.

**The UI lives on the student list.** The Deactivate button sits in the Operations column of `Students.vue` beside Edit, shown only for an active student (3a never reaches the server from the UI), and the confirmation is an `ElMessageBox.confirm` whose text is the consequences from the use case's Associated Information. The list page is where every student administration action already lives, and it is reachable only by instructors. Rejected: the student dashboard (`/students/:studentId`), which is the performance view and has no administration controls.

## Data model

No delta. `Student.enabled` (inherited from `PeerEvaluationUser`) already carries the state; `Team`, `Section`, `Activity`, and `PeerEvaluation` rows are untouched, as the SRS's [Business Domain Model](../requirements/software-requirements-specification.md#business-domain-model) defines them.

## Reuse & cross-cutting

- Authorization: the `AuthorizationManager` pattern of `architectural-design.md`; `StudentSecurityService` gains one query, in the fail-closed style its class comment requires.
- Error envelope: `IllegalArgumentException` → `400` `INVALID_ARGUMENT`, `ObjectNotFoundException` → `404`, `AccessDeniedException` → `403`, `AccountStatusException` → `401`, all through the existing `ExceptionHandlerAdvice`.
- Security per request: Spring Security's `oauth2ResourceServer().jwt()` takes the converter bean; nothing else in the filter chain changes. Login (`DaoAuthenticationProvider`) already refuses a disabled account through `MyUserPrincipal.isEnabled()`.
- Frontend: `ElMessageBox.confirm` as in `Sections.vue`; the `request` Axios instance; `ElMessage` for the step-6 notice.

## Tests

| Use case, flow | Level | Asserts |
|---|---|---|
| UC-STU-deactivate-student main, section instructor | integration | Bill (section instructor) `POST`s: `200`, the student's `enabled` is false, her team id is unchanged, and her past activities and peer evaluations are still returned by their existing routes |
| UC-STU-deactivate-student main, course admin | integration | The course admin, removed from the section in the test so he is admin only, `POST`s: `200` |
| UC-STU-deactivate-student 3a | integration | A second `POST` for the same student: `400` with the already-deactivated message; the student stays deactivated and nothing else changes |
| UC-STU-deactivate-student 3b | integration | Tim Cook (an instructor of another course) and a student (John, on himself and on a teammate): `403` with the `Result` envelope; the target stays active |
| UC-STU-deactivate-student unknown id | integration | `POST` for an id that names no student by a section instructor: `404` |
| UC-STU-deactivate-student 5a | E2E (Cypress, API stubbed) | Cancelling the dialog sends no `POST`, and the row still shows Enabled |
| UC-STU-deactivate-student step 4 | E2E (Cypress, API stubbed) | The dialog names the consequences before Confirm is offered; a deactivated row shows no Deactivate button |
| FR-SEC-active-account, per request | integration | John logs in, Bill deactivates him, John's next `GET /students/{john}` with the same token: `401` "User account is abnormal." |
| FR-SEC-active-account, login | integration | A deactivated student's `POST /users/login` with the right password: `401` |
| Trunk: `updateStudent` cannot lower `enabled` | unit | `updateStudent` with `enabled: false` from an instructor leaves the student enabled; with `enabled: true` on a deactivated student it raises it |
| BR-deactivated-student-standing, evaluate | unit | `addPeerEvaluation` whose evaluatee is deactivated throws `PeerEvaluationIllegalArgumentException`; an active evaluatee on the same team is saved |
| BR-deactivated-student-standing, report | unit | A deactivated student with one evaluation in week W is listed in W's report; the same student with none in W+1 is omitted from W+1's report; an active student with no evaluations is still listed |
| BR-submission-owed, teammates do not owe her | existing | `SubmissionStatusServiceTest` already asserts a deactivated teammate is neither listed nor required; cited, not rewritten |

## Open questions / risks

- **One read per request.** The converter adds a user lookup to every authenticated call. Not measured; at course scale (under a hundred users) it is a primary-key hit on a small table. If it ever shows in traces, cache the flag for the token's remaining lifetime rather than drop the check.
- **Reactivation by API only.** Until UC-STU-reactivate-student is built, raising `enabled` is possible only through `PUT /students/{id}` by a direct call. The edit form offers no control for it. Noted in that use case's Associated Information.
- **No frontend automated tests exist in this area today.** The two Cypress rows are the first; if the Cypress harness is not set up for stubbed API tests, they fall to a manual check named in the build pull request's Not verified.
