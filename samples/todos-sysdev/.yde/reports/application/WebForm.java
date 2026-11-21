package {#each config.package}{.}{#if more}.{/if}{/each}.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 確定した画面入力とメッセージから生成。空文字のみ null にし、空白は保持する。 */
public class {record.className} {{
{#each record.fields}
{#each annotations}{#if plain}{raw}{/if}{#if literal}{value:java-string}{/if}{/each}
    private {type} {name};
    public {type} get{accessor}() {{ return {name}; }
    public void set{accessor}({type} value) {{ this.{name} = {#if string}(value != null && value.isEmpty()) ? null : {/if}value; }
{/each}
{#each record.classes}
    public static class {name} {{
{#each fields}
{#each annotations}{#if plain}{raw}{/if}{#if literal}{value:java-string}{/if}{/each}
        private {type} {name};
        public {type} get{accessor}() {{ return {name}; }
        public void set{accessor}({type} value) {{ this.{name} = {#if string}(value != null && value.isEmpty()) ? null : {/if}value; }
{/each}
    }
{/each}
}
