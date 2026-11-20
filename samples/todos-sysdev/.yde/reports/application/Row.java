package {#each config.package}{.}{#if more}.{/if}{/each}.data.model;

/** 確定したテーブル設計から生成した行。初期値は DB の DEFAULT として扱います。 */
public class {model.className} {{
{#each model.columns}    private {java.qualifiedName} {name};
{/each}
{#each model.columns}    public {java.qualifiedName} get{accessor}() {{
        return {name};
    }}

    public void set{accessor}({java.qualifiedName} {name}) {{
        this.{name} = {name};
    }}

{/each}}}
