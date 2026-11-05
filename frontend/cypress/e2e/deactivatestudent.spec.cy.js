// UC-STU-deactivate-student: the Deactivate action on the student list, and BR-deactivated-student-standing on
// the peer evaluation form. The API calls these pages make are stubbed, so these tests check what the pages show
// and send; the backend's own tests cover who may deactivate and what deactivation leaves in place.
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const john = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  roles: 'student',
  sectionId: 2,
  sectionName: '2023-2024',
  teamId: 1,
  teamName: 'Team1'
}

const eric = { ...john, id: 5, username: 'e.hudson@abc.edu', firstName: 'Eric', lastName: 'Hudson', email: 'e.hudson@abc.edu' }

const jerry = { ...john, id: 6, username: 'j.moon@abc.edu', firstName: 'Jerry', lastName: 'Moon', email: 'j.moon@abc.edu', enabled: false }

const page = (students) => ({
  flag: true,
  code: 200,
  message: 'Find students successfully',
  data: {
    content: students,
    totalElements: students.length,
    totalPages: 1,
    size: 50,
    number: 0,
    numberOfElements: students.length,
    first: true,
    last: true,
    empty: students.length === 0
  }
})

describe('Deactivate a student', () => {
  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('POST', '**/students/search*', { body: page([john, jerry]) }).as('search')
    cy.intercept('POST', '**/students/*/deactivation', {
      delay: 1000, // Long enough to click Deactivate again while the request is in flight
      body: { flag: true, code: 200, message: 'Deactivate student successfully', data: { ...john, enabled: false } }
    }).as('deactivation')
    cy.visit('/students')
    cy.wait('@search')
  })

  it('warns about the consequences before offering Deactivate (step 4)', () => {
    cy.get('[data-cy=student-deactivate]').click()

    cy.get('.el-message-box__title').should('contain', 'Deactivate this student?')
    cy.get('.el-message-box__message')
      .should('contain', 'John Smith will no longer be able to log in')
      .and('contain', 'can no longer submit weekly activity reports or peer evaluations')
      .and('contain', 'neither asked nor allowed to evaluate her')
      .and('contain', 'only for the weeks already on record')
      .and('contain', 'team')
      .and('contain', 'submitted work are kept')
      .and('contain', 'does not delete')
    cy.get('.student-deactivate-confirm').should('contain', 'Deactivate').and('be.enabled')
    cy.get('.student-deactivate-cancel').should('contain', 'Cancel')
    cy.get('@deactivation.all').should('have.length', 0)
  })

  it('offers no Deactivate for a deactivated student (3a)', () => {
    cy.get('[data-cy=student-status]').should('have.length', 2)
    cy.get('[data-cy=student-deactivate]').should('have.length', 1) // John only; Jerry is already deactivated
    cy.contains('tr', 'Jerry').find('[data-cy=student-deactivate]').should('not.exist')
    cy.contains('tr', 'Jerry').find('[data-cy=student-status]').should('contain', 'Disabled')
  })

  it('sends nothing when the instructor cancels at the warning (5a)', () => {
    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.student-deactivate-cancel').click()

    cy.get('.el-message-box').should('not.be.visible')
    cy.get('@deactivation.all').should('have.length', 0)
    cy.contains('tr', 'John').find('[data-cy=student-status]').should('contain', 'Enabled')
  })

  it('deactivates once however often Deactivate is clicked, then reloads the list (steps 5 and 6)', () => {
    cy.intercept('POST', '**/students/search*', { body: page([{ ...john, enabled: false }, jerry]) }).as('reload')
    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.student-deactivate-confirm').click()
    cy.get('.student-deactivate-confirm').should('have.class', 'is-loading')
    cy.get('.student-deactivate-confirm').click({ force: true }) // A second click while the request is in flight

    cy.wait('@deactivation').its('request.url').should('match', /\/students\/4\/deactivation$/)
    cy.get('@deactivation.all').should('have.length', 1)
    cy.wait('@reload')
    cy.get('.el-message--success').should('contain', 'Deactivate student successfully')
    cy.contains('tr', 'John').find('[data-cy=student-status]').should('contain', 'Disabled')
    cy.get('[data-cy=student-deactivate]').should('not.exist')
  })

  it('offers no Status switch in the edit dialog (editing never deactivates)', () => {
    cy.intercept('GET', '**/students/4', { body: { flag: true, code: 200, message: 'Find student successfully', data: john } }).as('student')
    cy.contains('tr', 'John').find('[data-cy=student-edit]').click()
    cy.wait('@student')

    cy.get('.el-dialog').should('be.visible').and('contain', 'Edit a student')
    cy.get('.el-dialog').find('.el-switch').should('not.exist')
    cy.get('.el-dialog').should('not.contain', 'Status')
  })
})

describe('Peer evaluation form and a deactivated teammate', () => {
  it('lists only teammates whose account is active (BR-deactivated-student-standing)', () => {
    cy.login('j.smith@abc.edu', '123456')
    cy.intercept('GET', '**/students/teams/*', {
      body: { flag: true, code: 200, message: 'Find students by team id successfully', data: [john, eric, jerry] }
    }).as('teammates')
    // No evaluation submitted yet for the week, so the form lists the teammates to evaluate
    cy.intercept('GET', '**/evaluations/evaluators/*/week/*', {
      body: { flag: true, code: 200, message: 'Find evaluations successfully', data: [] }
    }).as('evaluations')
    cy.visit('/evaluations/submit-evaluations')
    cy.wait('@teammates')
    cy.wait('@evaluations')

    cy.get('.el-table__body tr').should('have.length', 2)
    cy.get('.el-table__body').should('contain', 'John Smith').and('contain', 'Eric Hudson').and('not.contain', 'Jerry Moon')
  })
})
