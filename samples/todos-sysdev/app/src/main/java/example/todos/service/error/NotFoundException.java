package example.todos.service.error;

/** 指定された対象が見つからない。 */
public class NotFoundException extends java.lang.RuntimeException {
    private final java.lang.String entity;
    private final java.lang.String id;

    public NotFoundException(java.lang.String entity, java.lang.String id) {
        super("error.notFound." + entity);
        this.entity = entity;
        this.id = id;
    }

    public java.lang.String getEntity() { return entity; }
    public java.lang.String getId() { return id; }
}
