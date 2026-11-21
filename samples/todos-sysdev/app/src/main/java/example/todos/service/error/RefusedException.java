package example.todos.service.error;

/** 業務の断り。実行中ならトランザクション全体を取り消す。 */
public class RefusedException extends java.lang.RuntimeException {
    private final BusinessViolation violation;

    public RefusedException(BusinessViolation violation) {
        super(violation.key());
        this.violation = violation;
    }

    public BusinessViolation getViolation() { return violation; }
}
