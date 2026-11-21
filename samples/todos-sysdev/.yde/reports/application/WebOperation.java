package {#each config.package}{.}{#if more}.{/if}{/each}.web.operation;

import {#each config.package}{.}{#if more}.{/if}{/each}.web.form.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.{record.service};
import {#each config.package}{.}{#if more}.{/if}{/each}.service.input.{record.inputClass};
import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.BusinessViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** 確定した Controller・Form・Service の対応から生成。HTTP の遷移は Controller が扱う。 */
@Component
public class {record.className} {{
    private final {record.service} service;
    private final Validator validator;

    public {record.className}({record.service} service, Validator validator) {{
        this.service = service;
        this.validator = validator;
    }

    public String execute({#if record.receiver}String id{#if record.formClass}, {/if}{/if}{#if record.formClass}{record.formClass} form{/if}) {{
        var input = toInput({#if record.receiver}id{#if record.formClass}, {/if}{/if}{#if record.formClass}form{/if});
        return service.{record.method}({#each record.arguments}input.get{accessor}(){#if more}, {/if}{/each});
    }
{#each record.confirm}
    public java.util.List<BusinessViolation> check({#if record.receiver}String id{#if record.formClass}, {/if}{/if}{#if record.formClass}{record.formClass} form{/if}) {{
        var input = toInput({#if record.receiver}id{#if record.formClass}, {/if}{/if}{#if record.formClass}form{/if});
        return service.{method}({#each record.arguments}input.get{accessor}(){#if more}, {/if}{/each});
    }
{/each}
    public {record.inputClass} toInput({#if record.receiver}String id{#if record.formClass}, {/if}{/if}{#if record.formClass}{record.formClass} form{/if}) {{
{#if record.formClass}        if (form == null) throw new IllegalArgumentException("入力フォームがありません");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
{/if}{#if record.receiver}        if (id == null || id.isEmpty()) throw new IllegalArgumentException("対象の ID がありません");
{/if}        var input = new {record.inputClass}();
{#each record.receiverFields}        input.set{accessor}(id);
{/each}{#each record.fields.assignments}{#each tokens}{raw}{/each}{/each}        return input;
    }
}
