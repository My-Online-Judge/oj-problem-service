package vn.thanhtuanle.contest;

/**
 * Who may take part in a contest while it runs (contest enrollment design §4.2): everyone signed in ({@link #OPEN}),
 * or only the users who registered first ({@link #REQUIRED}). After the end every contest is an archive for everyone.
 */
public enum ContestEnrollment {
    OPEN,
    REQUIRED
}
