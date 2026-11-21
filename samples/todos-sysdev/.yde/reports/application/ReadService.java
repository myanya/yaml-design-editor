package {#each config.package}{.}{#if more}.{/if}{/each}.service;

import java.util.*;
import org.springframework.stereotype.Service;
import {#each config.package}{.}{#if more}.{/if}{/each}.data.mapper.*;

/** 確定した一覧の読出しをまとめる。更新は行わない。 */
@Service
public class {record.name} {{
{#each mappers}    private final {name} {variable};
{/each}    public {record.name}({#each mappers}{name} {variable}{#if more}, {/if}{/each}) {{
{#each mappers}        this.{variable}={variable};
{/each}    }}
{#each record.methods}
    public Map<String,Object> {method}(Map<String,Object> params) {{
        var result=new LinkedHashMap<String,Object>();
{#each queries}        {{
{#if parameters}            var input=new {mapper}.{inputClass}();
{#each parameters}            input.set{accessor}(({type})params.get({name:java-string}));
{/each}{/if}            result.put({call:java-string},{variable}.{name}({#if parameters}input{/if}));
        }}
{/each}        return result;
    }}
{/each}}}
