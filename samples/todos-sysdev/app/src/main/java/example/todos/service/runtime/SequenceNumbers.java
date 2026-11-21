package example.todos.service.runtime;

import java.math.BigInteger;
import java.util.Objects;

/** MAX の取得と書込みは OperationRunner.execute の同じ操作内で行う。 */
public final class SequenceNumbers {
    private SequenceNumbers() {}

    public static String nextId(String previous, String prefix, int digits) {
        Objects.requireNonNull(prefix);
        if (prefix.isEmpty() || digits < 1) throw new IllegalArgumentException("接頭辞と桁数を指定してください");
        BigInteger number = BigInteger.ZERO;
        if (previous != null) {
            if (!previous.startsWith(prefix)) throw new IllegalStateException("採番の接頭辞が一致しません: " + previous);
            String suffix = previous.substring(prefix.length());
            if (!suffix.matches("[0-9]+")) throw new IllegalStateException("採番の末尾が整数ではありません: " + previous);
            number = new BigInteger(suffix);
        }
        String next = number.add(BigInteger.ONE).toString();
        return prefix + "0".repeat(Math.max(0, digits - next.length())) + next;
    }

    public static long nextPosition(Long previous) {
        if (previous == null) return 1L;
        if (previous < 0) throw new IllegalStateException("並び順が負です: " + previous);
        return Math.addExact(previous, 1L);
    }
}
