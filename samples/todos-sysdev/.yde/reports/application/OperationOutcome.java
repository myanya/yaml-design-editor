package {#each config.package}{.}{#if more}.{/if}{/each}.service.runtime;

import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.BusinessViolation;
import java.util.List;

/** 実行と確認が共有する結果。確認はデータを書き込まない。 */
public record OperationOutcome(String id, List<BusinessViolation> violations) {{
    public OperationOutcome {{ violations = List.copyOf(violations); }
}
