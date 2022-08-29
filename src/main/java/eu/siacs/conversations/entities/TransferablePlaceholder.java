package eu.siacs.conversations.entities;

public class TransferablePlaceholder implements Transferable {

	private final int status;

	public TransferablePlaceholder(int status) {
		this.status = status;
	}
	@Override
	public boolean start() {
		return false;
	}

	@Override
	public int getStatus() {
		return status;
	}

	@Override
	public Long getFileSize() {
		return null;
	}

	@Override
	public int getProgress() {
		return 0;
	}

	@Override
	public void cancel() {

	}
}
