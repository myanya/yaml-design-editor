package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;
import {#each config.package}{.}{#if more}.{/if}{/each}.web.form.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.web.operation.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.BusinessViolation;

/** 画面と Service の対応検査を通った呼出しだけを登録する。 */
@Component
public class WebActions {{
    record Action(Supplier<Object> form, BiFunction<String,Object,String> execute,
                  BiFunction<String,Object,List<BusinessViolation>> check) {{}}
    final Map<String,Action> actions=new LinkedHashMap<>();
    public WebActions({#each record.operations}{className} op{index}{#if more}, {/if}{/each}) {{
{#each record.operations}
        actions.put({action:java-string},new Action(
            () -> {#if formClass}new {formClass}(){/if}{#if noForm}null{/if},
            (id,form) -> op{index}.execute({#if receiver}id{#if formClass}, {/if}{/if}{#if formClass}({formClass})form{/if}),
            (id,form) -> {#if hasConfirm}op{index}.check({#if receiver}id{#if formClass}, {/if}{/if}{#if formClass}({formClass})form{/if}){/if}{#if noConfirm}List.of(){/if}));
{/each}    }}
}}
