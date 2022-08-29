package eu.siacs.conversations.xmpp;

public interface OnUpdateBlocklist {
	// Use an enum instead of a boolean to make sure we don't run into the boolean trap
	// (`onUpdateBlocklist(true)' doesn't read well, and could be confusing).
    enum Status {
		BLOCKED,
		UNBLOCKED
	}

	@SuppressWarnings("MethodNameSameAsClassName")
    void OnUpdateBlocklist(final Status status);
}
