package {#each config.package}{.}{#if more}.{/if}{/each}.service.error;

{#if record.refused}
/** 業務の断り。実行中ならトランザクション全体を取り消す。 */
public class {record.name} extends java.lang.RuntimeException {{
    private final BusinessViolation violation;

    public {record.name}(BusinessViolation violation) {{
        super(violation.key());
        this.violation = violation;
    }

    public BusinessViolation getViolation() {{ return violation; }
}
{/if}
{#if record.notFound}
/** 指定された対象が見つからない。 */
public class {record.name} extends java.lang.RuntimeException {{
    private final java.lang.String entity;
    private final java.lang.String id;

    public {record.name}(java.lang.String entity, java.lang.String id) {{
        super("error.notFound." + entity);
        this.entity = entity;
        this.id = id;
    }

    public java.lang.String getEntity() {{ return entity; }
    public java.lang.String getId() {{ return id; }
}
{/if}
