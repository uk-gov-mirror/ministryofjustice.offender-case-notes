package uk.gov.justice.hmpps.casenotes.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.justice.hmpps.casenotes.config.AuthAwareAuthenticationToken;
import uk.gov.justice.hmpps.casenotes.filters.OffenderCaseNoteFilter;
import uk.gov.justice.hmpps.casenotes.model.OffenderCaseNote;
import uk.gov.justice.hmpps.casenotes.model.OffenderCaseNote.OffenderCaseNoteBuilder;
import uk.gov.justice.hmpps.casenotes.model.SensitiveCaseNoteType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import static java.time.LocalDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
public class OffenderCaseNoteRepositoryTest {

    private static final String PARENT_TYPE = "POM";
    private static final String SUB_TYPE = "GEN";
    public static final String OFFENDER_IDENTIFIER = "A1234BD";

    @Autowired
    private OffenderCaseNoteRepository repository;

    @Autowired
    private CaseNoteTypeRepository caseNoteTypeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SensitiveCaseNoteType genType;

    @BeforeEach
    public void setUp() {
        final var jwt = Jwt.withTokenValue("some").subject("anonymous").header("head", "something").build();
        SecurityContextHolder.getContext().setAuthentication(
                new AuthAwareAuthenticationToken(jwt, "userId", Collections.emptyList()));
        genType = caseNoteTypeRepository.findSensitiveCaseNoteTypeByParentType_TypeAndType(PARENT_TYPE, SUB_TYPE);
    }

    @Test
    public void testPersistCaseNote() {

        final var caseNote = transientEntity(OFFENDER_IDENTIFIER);

        final var persistedEntity = repository.save(caseNote);

        TestTransaction.flagForCommit();
        TestTransaction.end();

        assertThat(persistedEntity.getId()).isNotNull();

        TestTransaction.start();

        final var retrievedEntity = repository.findById(persistedEntity.getId()).orElseThrow();

        assertThat(retrievedEntity).usingRecursiveComparison().ignoringFields("occurrenceDateTime", "sensitiveCaseNoteType", "eventId", "createDateTime", "modifyDateTime").isEqualTo(caseNote);
        assertThat(retrievedEntity.getCreateDateTime()).isEqualToIgnoringNanos(caseNote.getCreateDateTime());
        assertThat(retrievedEntity.getModifyDateTime()).isEqualToIgnoringNanos(caseNote.getModifyDateTime());
        assertThat(retrievedEntity.getOccurrenceDateTime()).isEqualToIgnoringNanos(caseNote.getOccurrenceDateTime());
        assertThat(retrievedEntity.getSensitiveCaseNoteType()).isEqualTo(caseNote.getSensitiveCaseNoteType());
        assertThat(retrievedEntity.getCreateUserId()).isEqualTo("anonymous");
    }

    @Test
    @WithAnonymousUser
    public void testPersistCaseNoteAndAmendment() {

        final var caseNote = transientEntity(OFFENDER_IDENTIFIER);

        caseNote.addAmendment("Another Note 0", "someuser", "Some User", "user id");
        assertThat(caseNote.getAmendments()).hasSize(1);

        final var persistedEntity = repository.save(caseNote);

        TestTransaction.flagForCommit();
        TestTransaction.end();

        assertThat(persistedEntity.getId()).isNotNull();

        TestTransaction.start();

        final var retrievedEntity = repository.findById(persistedEntity.getId()).orElseThrow();

        retrievedEntity.addAmendment("Another Note 1", "someuser", "Some User", "user id");
        retrievedEntity.addAmendment("Another Note 2", "someuser", "Some User", "user id");

        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();

        final var retrievedEntity2 = repository.findById(persistedEntity.getId()).orElseThrow();

        assertThat(retrievedEntity2.getAmendments()).hasSize(3);

        assertThat(retrievedEntity2.getAmendments().first().getNoteText()).isEqualTo("Another Note 0");
        final var offenderCaseNoteAmendment3 = new ArrayList<>(retrievedEntity2.getAmendments()).get(2);
        assertThat(offenderCaseNoteAmendment3.getNoteText()).isEqualTo("Another Note 2");

        retrievedEntity2.addAmendment("Another Note 3", "USER1", "Mickey Mouse", "user id");

        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();

        final var retrievedEntity3 = repository.findById(persistedEntity.getId()).orElseThrow();

        assertThat(retrievedEntity3.getAmendments()).hasSize(4);

        assertThat(retrievedEntity3.getAmendments().last().getNoteText()).isEqualTo("Another Note 3");
    }

    @Test
    public void testOffenderCaseNoteFilter() {
        final var entity = OffenderCaseNote.builder()
                .occurrenceDateTime(now())
                .locationId("BOB")
                .authorUsername("FILTER")
                .authorUserId("some id")
                .authorName("Mickey Mouse")
                .offenderIdentifier(OFFENDER_IDENTIFIER)
                .sensitiveCaseNoteType(genType)
                .noteText("HELLO")
                .build();
        repository.save(entity);

        final var allCaseNotes = repository.findAll(OffenderCaseNoteFilter.builder()
                .type(" ").subType(" ").authorUsername(" ").locationId(" ").offenderIdentifier(" ").build());
        assertThat(allCaseNotes.size()).isGreaterThan(0);

        final var caseNotes = repository.findAll(OffenderCaseNoteFilter.builder()
                .type(PARENT_TYPE).subType(SUB_TYPE).authorUsername("FILTER").locationId("BOB").offenderIdentifier(OFFENDER_IDENTIFIER).build());
        assertThat(caseNotes).hasSize(1);
    }

    @Test
    public void testAmendmentUpdatesCaseNoteModification() {
        final var twoDaysAgo = now().minusDays(2);

        final var noteText = "updates old note";
        final var oldNote = repository.save(transientEntityBuilder(OFFENDER_IDENTIFIER).noteText(noteText).build());

        final var noteTextWithAmendment = "updates old note with old amendment";
        final var oldNoteWithOldAmendment = repository.save(transientEntityBuilder(OFFENDER_IDENTIFIER).noteText(noteTextWithAmendment).build());
        oldNoteWithOldAmendment.addAmendment("Some amendment", "someuser", "Some User", "user id");
        repository.save(oldNoteWithOldAmendment);

        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();

        // set the notes to two days ago
        final var update = jdbcTemplate.update("update offender_case_note set modify_date_time = ? where offender_case_note_id in (?, ?)", twoDaysAgo,
                oldNote.getId().toString(), oldNoteWithOldAmendment.getId().toString());
        assertThat(update).isEqualTo(2);

        // now add an amendment
        final var retrievedOldNote = repository.findById(oldNote.getId()).orElseThrow();
        retrievedOldNote.addAmendment("An amendment", "anotheruser", "Another User", "user id");
        repository.save(retrievedOldNote);

        final var yesterday = now().minusDays(1);
        final var rows = repository.findBySensitiveCaseNoteType_ParentType_TypeInAndModifyDateTimeAfterOrderByModifyDateTime(Set.of("POM"), yesterday, Pageable.unpaged());
        assertThat(rows).extracting(OffenderCaseNote::getNoteText).contains(noteText).doesNotContain(noteTextWithAmendment);
    }


    @Test
    public void findByModifiedDate() {
        final var twoDaysAgo = now().minusDays(2);

        final var oldNoteText = "old note";
        final var oldNote = repository.save(transientEntityBuilder(OFFENDER_IDENTIFIER).noteText(oldNoteText).build());

        final var newNoteText = "new note";
        repository.save(transientEntityBuilder(OFFENDER_IDENTIFIER).noteText(newNoteText).build());

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        // set the old notes two days ago so won't be returned
        final var update = jdbcTemplate.update("update offender_case_note set modify_date_time = ? where offender_case_note_id in (?)", twoDaysAgo, oldNote.getId().toString());
        assertThat(update).isEqualTo(1);

        final var yesterday = now().minusDays(1);
        final var rows = repository.findBySensitiveCaseNoteType_ParentType_TypeInAndModifyDateTimeAfterOrderByModifyDateTime(Set.of("POM", "BOB"), yesterday, Pageable.unpaged());
        assertThat(rows).extracting(OffenderCaseNote::getNoteText).contains(newNoteText).doesNotContain(oldNoteText);
    }

    @Test
    public void testGenerationOfEventId() {
        final var note = repository.save(transientEntity(OFFENDER_IDENTIFIER));

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertThat(repository.findById(note.getId()).orElseThrow().getEventId()).isLessThan(0);
    }

    @Test
    public void testDeleteCaseNotes() {

        final var persistedEntity = repository.save(transientEntityBuilder("X1111XX").noteText("note to delete").build());

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var deletedCaseNotes = repository.deleteOffenderCaseNoteByOffenderIdentifier("X1111XX");
        assertThat(deletedCaseNotes).isEqualTo(1);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertThat(repository.findById(persistedEntity.getId()).isEmpty());

        final var sql = String.format("SELECT COUNT(*) FROM offender_case_note Where offender_case_note_id = '%s'", persistedEntity.getId().toString());
        final var caseNoteCountAfter = jdbcTemplate.queryForObject(sql, Integer.class);
        assertThat(caseNoteCountAfter).isEqualTo(0);
    }

    @Test
    public void testDeleteOfSoftDeletedCaseNotes() {

        final var persistedEntity = repository.save(transientEntityBuilder("X2111XX").noteText("note to delete").softDeleted(true).build());

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var deletedCaseNotes = repository.deleteOffenderCaseNoteByOffenderIdentifier("X2111XX");
        assertThat(deletedCaseNotes).isEqualTo(1);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertThat(repository.findById(persistedEntity.getId()).isEmpty());
        TestTransaction.end();

        final var sql = String.format("SELECT COUNT(*) FROM offender_case_note Where offender_case_note_id = '%s'", persistedEntity.getId().toString());
        final var caseNoteCountAfter = jdbcTemplate.queryForObject(sql, Integer.class);
        assertThat(caseNoteCountAfter).isEqualTo(0);
    }

    @Test
    public void testDeleteOfSoftDeletedCaseNotesAmendments() {

        final var persistedEntity = repository.save(transientEntityBuilder("X3111XX")
                .noteText("note to delete")
                .softDeleted(true)
                .build());
        persistedEntity.addAmendment("Another Note 0", "someuser", "Some User", "user id");
        repository.save(persistedEntity);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        final var caseNoteCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM offender_case_note_amendment", Integer.class);
        assertThat(caseNoteCountBefore).isEqualTo(3);

        TestTransaction.start();
        repository.deleteOffenderCaseNoteAmendmentsByOffenderIdentifier("X3111XX");
        final var deletedCaseNotes = repository.deleteOffenderCaseNoteByOffenderIdentifier("X3111XX");
        assertThat(deletedCaseNotes).isEqualTo(1);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        assertThat(repository.findById(persistedEntity.getId()).isEmpty());
        TestTransaction.end();

        final var caseNoteCountAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM offender_case_note_amendment", Integer.class);
        assertThat(caseNoteCountAfter).isEqualTo(2);

    }

    @Test
    @WithAnonymousUser
    public void testPersistCaseNoteAndAmendmentAndThenDelete() {

        final var caseNote = transientEntity(OFFENDER_IDENTIFIER);
        caseNote.addAmendment("Another Note 0", "someuser", "Some User", "user id");
        final var persistedEntity = repository.save(caseNote);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        final var retrievedEntity = repository.findById(persistedEntity.getId()).orElseThrow();

        retrievedEntity.addAmendment("Another Note 1", "someuser", "Some User", "user id");
        retrievedEntity.addAmendment("Another Note 2", "someuser", "Some User", "user id");

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        repository.deleteOffenderCaseNoteAmendmentsByOffenderIdentifier(caseNote.getOffenderIdentifier());
        final var deletedEntities = repository.deleteOffenderCaseNoteByOffenderIdentifier(caseNote.getOffenderIdentifier());

        assertThat(deletedEntities).isEqualTo(1);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var deletedEntity = repository.findById(persistedEntity.getId());
        assertThat(deletedEntity).isEmpty();
    }

    @Test
    @WithAnonymousUser
    public void testModifyOffenderIdentifier() {
        final var caseNote = transientEntity("A1234ZZ");
        caseNote.addAmendment("Another Note 0", "someuser", "Some User", "user id");
        final var persistedEntity = repository.save(caseNote);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var retrievedCaseNote = repository.findById(persistedEntity.getId()).orElseThrow();
        assertThat(retrievedCaseNote.getOffenderIdentifier()).isEqualTo("A1234ZZ");

        TestTransaction.end();
        TestTransaction.start();

        final var rows = repository.updateOffenderIdentifier("A1234ZZ", OFFENDER_IDENTIFIER);

        assertThat(rows).isEqualTo(1);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var modifiedIdentity = repository.findById(persistedEntity.getId()).orElseThrow();
        assertThat(modifiedIdentity.getOffenderIdentifier()).isEqualTo(OFFENDER_IDENTIFIER);
    }

    @Test
    @WithAnonymousUser
    public void testModifyOffenderIdentifierWhenACaseNoteIsSoftDeleted() {
        final var caseNote = transientEntity("A2234ZZ");
        caseNote.addAmendment("Another Note 0", "someuser", "Some User", "user id");
        final var persistedEntity = repository.save(caseNote);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var retrievedCaseNote = repository.findById(persistedEntity.getId()).orElseThrow();
        assertThat(retrievedCaseNote.getOffenderIdentifier()).isEqualTo("A2234ZZ");

        repository.delete(retrievedCaseNote);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var retrievedCaseNote2 = repository.findById(persistedEntity.getId());
        assertThat(retrievedCaseNote2).isEmpty();

        final var rows = repository.updateOffenderIdentifier("A2234ZZ", OFFENDER_IDENTIFIER);

        assertThat(rows).isEqualTo(1);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        final var sql = String.format("SELECT offender_identifier FROM offender_case_note Where offender_case_note_id = '%s'", persistedEntity.getId().toString());
        final var caseNoteOffenderIdentifierIgnoreSoftDelete = jdbcTemplate.queryForObject(sql, String.class);
        assertThat(caseNoteOffenderIdentifierIgnoreSoftDelete).isEqualTo(OFFENDER_IDENTIFIER);
    }

    @Test
    public void testOffenderCaseNoteSoftDeleted() {
        final var caseNote = transientEntity("A2345AB");
        final var persistedEntity = repository.save(caseNote);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var retrievedCaseNote = repository.findById(persistedEntity.getId()).orElseThrow();
        repository.delete(retrievedCaseNote);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var retrievedSoftDeleteCaseNote = repository.findById(persistedEntity.getId());
        assertThat(retrievedSoftDeleteCaseNote).isEmpty();
    }


    @Test
    @WithAnonymousUser
    public void testRetrieveASoftDeletedFalseCaseNote() {

        final var persistedEntity = repository.save(transientEntityBuilder("X4111XX").noteText("note to retrieve").softDeleted(false).build());

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var caseNoteId = persistedEntity.getId();

        final var caseNote = repository.findById(caseNoteId).orElseThrow();
        assertThat(caseNote.getOffenderIdentifier()).isEqualTo("X4111XX");

        TestTransaction.end();

        final var sql = String.format("SELECT offender_identifier FROM offender_case_note Where offender_case_note_id = '%s'", persistedEntity.getId().toString());
        final var caseNoteOffenderIdentifierIgnoreSoftDelete = jdbcTemplate.queryForObject(sql, String.class);
        assertThat(caseNoteOffenderIdentifierIgnoreSoftDelete).isEqualTo("X4111XX");
    }

    @Test
    @WithAnonymousUser
    public void testRetrieveASoftDeletedTrueCaseNote() {

        final var persistedEntity = repository.save(transientEntityBuilder("X5111XX").noteText("note to retrieve").softDeleted(true).build());

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final var caseNoteId = persistedEntity.getId();
        final var caseNote = repository.findById(caseNoteId);
        assertThat(caseNote).isEmpty();

        TestTransaction.end();

        final var sql = String.format("SELECT offender_identifier FROM offender_case_note Where offender_case_note_id = '%s'", persistedEntity.getId().toString());
        final var caseNoteOffenderIdentifierIgnoreSoftDelete = jdbcTemplate.queryForObject(sql, String.class);
        assertThat(caseNoteOffenderIdentifierIgnoreSoftDelete).isEqualTo("X5111XX");
    }

    @Test
    @WithAnonymousUser
    public void testThatSoftDeleteDoesntCascadeFromCaseNoteToAmendments() {
        final var persistedEntity = repository.save(transientEntityBuilder("X9111XX")
                .noteText("note to delete")
                .build());
        persistedEntity.addAmendment("Another Note 0", "someuser", "Some User", "user id");
        persistedEntity.addAmendment("Another Note 1", "someuser", "Some User", "user id");
        repository.save(persistedEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        final var retrievedEntity = repository.findById(persistedEntity.getId()).orElseThrow();
        TestTransaction.end();
        TestTransaction.start();
        repository.deleteById(retrievedEntity.getId());
        TestTransaction.flagForCommit();
        TestTransaction.end();

        final var sql = String.format("SELECT soft_deleted FROM offender_case_note_amendment Where offender_case_note_amendment_id = '%s'", persistedEntity.getAmendments().first().getId());
        assertThat(jdbcTemplate.queryForObject(sql, Boolean.class)).isFalse();


    }


    private OffenderCaseNote transientEntity(final String offenderIdentifier) {
        return transientEntityBuilder(offenderIdentifier).build();
    }

    private OffenderCaseNoteBuilder transientEntityBuilder(final String offenderIdentifier) {
        return OffenderCaseNote.builder()
                .occurrenceDateTime(now())
                .locationId("MDI")
                .authorUsername("USER2")
                .authorUserId("some id")
                .authorName("Mickey Mouse")
                .offenderIdentifier(offenderIdentifier)
                .sensitiveCaseNoteType(genType)
                .noteText("HELLO");

    }
}
