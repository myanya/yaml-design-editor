package {#each config.package}{.}{#if more}.{/if}{/each}.service.input;

/** 確定した操作入力と Service の引数から生成。 */
public class {record.className} {{
{#each record.arguments}
    private {type} {name};
    public {type} get{accessor}() {{ return {name}; }
    public void set{accessor}({type} value) {{ this.{name} = value; }
{/each}
{#each record.classes}
    public static class {name} {{
{#each fields}
        private {type} {name};
        public {type} get{accessor}() {{ return {name}; }
        public void set{accessor}({type} value) {{ this.{name} = value; }
{/each}
    }
{/each}
}
