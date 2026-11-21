package {#each config.package}{.}{#if more}.{/if}{/each}.service.state;

/** 確定した状態設計から生成。 */
public enum {record.name} {{
{#each record.values}
    {constant}({label:java-string}){#if more},{/if}
{/each}
    ;

    private final java.lang.String label;
    {record.name}(java.lang.String label) {{ this.label = label; }
    public java.lang.String getLabel() {{ return label; }

{#each record.groups}
    public boolean {method}() {{
        return {#each values}this == {.}{#if more} || {/if}{/each};
    }
{/each}

    public boolean isTerminal() {{
        return false{#each record.terminal} || this == {.}{/each};
    }

    public boolean allows(java.lang.String action) {{
{#each record.transitions}
        if ({action:java-string}.equals(action) && ({#each from}this == {.}{#if more} || {/if}{/each})) return true;
{/each}
        return false;
    }

    public static {record.name} next(java.lang.String action, {record.name} current) {{
{#each record.entry}
        if (current == null && {action:java-string}.equals(action)) return {to};
{/each}
{#each record.transitions}
        if ({action:java-string}.equals(action) && ({#each from}current == {.}{#if more} || {/if}{/each})) return {to};
{/each}
        throw new java.lang.IllegalArgumentException("現在の状態ではこの操作を実行できません: " + action);
    }
}
