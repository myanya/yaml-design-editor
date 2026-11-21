package {#each config.package}{.}{#if more}.{/if}{/each}.service;

import {#each config.package}{.}{#if more}.{/if}{/each}.data.model.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.data.mapper.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.input.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.runtime.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.BusinessViolation;
import org.springframework.stereotype.Service;

/** 確定した処理設計・入力・Mapper の対応から生成。 */
@Service
public class {record.name} {{
    private final OperationRunner runner;
    private final java.time.Clock clock;
{#each record.mappers}    private final {name} {variable};
{/each}
    public {record.name}(OperationRunner runner, java.time.Clock clock{#each record.mappers}, {name} {variable}{/each}) {{
        this.runner = runner;
        this.clock = clock;
{#each record.mappers}        this.{variable} = {variable};
{/each}    }
{#each record.operations}
    public String {method}({#each arguments}{type} {name}{#if more}, {/if}{/each}) {{
        var input = new {input.className}();
{#each arguments}        input.set{accessor}({name});
{/each}        return runner.execute(() -> run{method}(input, false)).id();
    }
{#each confirm}
    public java.util.List<BusinessViolation> {method}({#each arguments}{type} {name}{#if more}, {/if}{/each}) {{
        var input = new {inputClass}();
{#each arguments}        input.set{accessor}({name});
{/each}        return runner.preview(() -> run{operationMethod}(input, true)).violations();
    }
{/each}
    private OperationOutcome run{method}({input.className} input, boolean preview) {{
{#each tokens}{#if errorClass}{#each config.package}{.}.{/each}service.error.{errorClass}{/if}{#if plain}{raw}{/if}{#if literal}{value:java-string}{/if}{/each}    }
{/each}
{#if read}{#each read.methods}
{#each queries}
    public {mapper}.{resultClass} {method}(java.lang.String id) {{
        var input = new {mapper}.{inputClass}();
        input.setId(id);
        return {variable}.{name}(input);
    }
{/each}
{/each}{/if}}
