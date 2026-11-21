package {#each config.package}{.}{#if more}.{/if}{/each}.service.runtime;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 一つのアプリ内で、採番前からトランザクション完了まで操作の順番を保つ。 */
@Component
public final class OperationRunner {{
    private final ReentrantLock lock = new ReentrantLock(true);
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public OperationRunner(PlatformTransactionManager manager) {{
        write = new TransactionTemplate(Objects.requireNonNull(manager));
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
    }}

    public <T> T execute(Supplier<T> operation) {{
        return run(write, false, operation);
    }}

    /** 確認の処理は読むだけにする。誤って行った DB 更新も確定させない。 */
    public <T> T preview(Supplier<T> inspection) {{
        return run(read, true, inspection);
    }}

    private <T> T run(TransactionTemplate transaction, boolean preview, Supplier<T> body) {{
        Objects.requireNonNull(body);
        // 外側のトランザクションへ参加すると、その確定より先にロックが外れる。
        if (TransactionSynchronizationManager.isActualTransactionActive() || lock.isHeldByCurrentThread()) {{
            throw new IllegalStateException("操作は既存のトランザクションや別の操作の中から呼べません");
        }}
        lock.lock();
        try {{
            return transaction.execute(status -> {{
                if (preview) status.setRollbackOnly();
                return body.get();
            }});
        }} finally {{
            // execute が返る時点で commit / rollback と完了通知が終わっている。
            lock.unlock();
        }}
    }}
}}
