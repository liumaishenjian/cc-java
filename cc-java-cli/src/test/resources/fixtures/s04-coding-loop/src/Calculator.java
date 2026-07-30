public final class Calculator {

    private Calculator() {
    }

    static int add(int left, int right) {
        return left + right;
    }

    public static void main(String[] args) {
        if (args.length != 1 || !"--self-test".equals(args[0])) {
            throw new IllegalArgumentException("expected --self-test");
        }
        int actual = add(2, 3);
        if (actual != 5) {
            throw new AssertionError("expected 5 but was " + actual);
        }
        System.out.println("ACCEPTANCE_OK");
    }
}
