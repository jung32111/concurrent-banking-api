package com.bank.idempotency;

import com.bank.entity.IdempotencyKey;
import com.bank.exception.IdempotencyHashMismatchException;
import com.bank.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DbIdempotencyStoreTest {

    @Mock IdempotencyKeyRepository repository;
    @Mock IdempotencyKeyInserter inserter;

    @InjectMocks DbIdempotencyStore store;

    private static final String KEY  = "idem-key-001";
    private static final String HASH = "abc123hash";

    @Test
    @DisplayName("최초 요청 — tryInsert 성공 시 Fresh 반환")
    void getOrCreate_firstRequest_returnsFresh() {
        given(inserter.tryInsert(KEY, HASH)).willReturn(true);

        GetOrCreateResult result = store.getOrCreate(KEY, HASH);

        assertThat(result).isInstanceOf(GetOrCreateResult.Fresh.class);
    }

    @Test
    @DisplayName("처리 중 — responseBody null 이면 InProgress 반환")
    void getOrCreate_inProgress_returnsInProgress() {
        IdempotencyKey entity = incompleteEntity(KEY, HASH);
        given(inserter.tryInsert(KEY, HASH)).willReturn(false);
        given(repository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entity));

        GetOrCreateResult result = store.getOrCreate(KEY, HASH);

        assertThat(result).isInstanceOf(GetOrCreateResult.InProgress.class);
    }

    @Test
    @DisplayName("응답 완료 후 재요청 — Replay 반환, 상태코드·본문 일치")
    void getOrCreate_completed_returnsReplay() {
        IdempotencyKey entity = completedEntity(KEY, HASH, 200, "{\"success\":true}");
        given(inserter.tryInsert(KEY, HASH)).willReturn(false);
        given(repository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entity));

        GetOrCreateResult result = store.getOrCreate(KEY, HASH);

        assertThat(result).isInstanceOf(GetOrCreateResult.Replay.class);
        GetOrCreateResult.Replay replay = (GetOrCreateResult.Replay) result;
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.responseBody()).isEqualTo("{\"success\":true}");
    }

    @Test
    @DisplayName("해시 불일치 — IdempotencyHashMismatchException 발생")
    void getOrCreate_hashMismatch_throwsException() {
        IdempotencyKey entity = incompleteEntity(KEY, "original-hash");
        given(inserter.tryInsert(KEY, "different-hash")).willReturn(false);
        given(repository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> store.getOrCreate(KEY, "different-hash"))
                .isInstanceOf(IdempotencyHashMismatchException.class);
    }

    @Test
    @DisplayName("saveResponse — 레코드에 응답 저장")
    void saveResponse_updatesExistingRecord() {
        IdempotencyKey entity = incompleteEntity(KEY, HASH);
        given(repository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entity));

        store.saveResponse(KEY, HASH, 201, "{\"id\":1}");

        verify(repository).findByIdempotencyKey(KEY);
        assertThat(entity.getHttpStatus()).isEqualTo(201);
        assertThat(entity.getResponseBody()).isEqualTo("{\"id\":1}");
    }

    private IdempotencyKey incompleteEntity(String key, String hash) {
        IdempotencyKey entity = IdempotencyKey.forNewRequest(key, hash);
        return entity;
    }

    private IdempotencyKey completedEntity(String key, String hash, int status, String body) {
        IdempotencyKey entity = IdempotencyKey.forNewRequest(key, hash);
        entity.fillResponse(status, body);
        return entity;
    }
}
