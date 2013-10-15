package us.kbase.workspace.database.mongo;

import us.kbase.workspace.database.ResolvedWorkspaceID;

public class ResolvedMongoWSID implements ResolvedWorkspaceID {
	
	private final long id;
	
	public ResolvedMongoWSID(long id) {
		if (id < 1) {
			throw new IllegalArgumentException("ID must be >0");
		}
		this.id = id;
	}

	@Override
	public long getID() {
		return id;
	}

	@Override
	public String toString() {
		return "ResolvedMongoWSID [id=" + id + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) (id ^ (id >>> 32));
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof ResolvedMongoWSID)) {
			return false;
		}
		ResolvedMongoWSID other = (ResolvedMongoWSID) obj;
		if (id != other.id) {
			return false;
		}
		return true;
	}

}
