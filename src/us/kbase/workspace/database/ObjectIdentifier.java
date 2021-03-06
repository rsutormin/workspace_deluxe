package us.kbase.workspace.database;

import static us.kbase.workspace.database.Util.xorNameId;
import static us.kbase.workspace.database.ObjectIDNoWSNoVer.checkObjectName;
import static us.kbase.common.utils.StringUtils.checkString;

public class ObjectIdentifier {
	
	public final static String REFERENCE_SEP = "/"; //this cannot be a legal object/workspace char
	
	private final WorkspaceIdentifier wsi;
	private final String name;
	private final Long id;
	private final Integer version;
	
	public ObjectIdentifier(WorkspaceIdentifier wsi, String name) {
		if (wsi == null) {
			throw new IllegalArgumentException("wsi cannot be null");
		}
		checkObjectName(name);
		this.wsi = wsi;
		this.name = name;
		this.id = null;
		this.version = null;
	}
	
	public ObjectIdentifier(WorkspaceIdentifier wsi, String name, int version) {
		if (wsi == null) {
			throw new IllegalArgumentException("wsi cannot be null");
		}
		checkObjectName(name);
		if (version < 1) {
			throw new IllegalArgumentException("Object version must be > 0");
		}
		this.wsi = wsi;
		this.name = name;
		this.id = null;
		this.version = version;
	}
	
	public ObjectIdentifier(WorkspaceIdentifier wsi, long id) {
		if (wsi == null) {
			throw new IllegalArgumentException("wsi cannot be null");
		}
		if (id < 1) {
			throw new IllegalArgumentException("Object id must be > 0");
		}
		this.wsi = wsi;
		this.name = null;
		this.id = id;
		this.version = null;
	}
	
	public ObjectIdentifier(WorkspaceIdentifier wsi, long id, int version) {
		if (wsi == null) {
			throw new IllegalArgumentException("wsi cannot be null");
		}
		if (id < 1) {
			throw new IllegalArgumentException("Object id must be > 0");
		}
		if (version < 1) {
			throw new IllegalArgumentException("Object version must be > 0");
		}
		this.wsi = wsi;
		this.name = null;
		this.id = id;
		this.version = version;
	}
	
	public WorkspaceIdentifier getWorkspaceIdentifier() {
		return wsi;
	}

	public String getName() {
		return name;
	}

	public Long getId() {
		return id;
	}

	public Integer getVersion() {
		return version;
	}
	
	public String getIdentifierString() {
		if (getId() == null) {
			return getName();
		}
		return "" + getId();
	}

	public String getWorkspaceIdentifierString() {
		return wsi.getIdentifierString();
	}
	
	public ObjectIDResolvedWS resolveWorkspace(ResolvedWorkspaceID rwsi) {
		if (rwsi == null) {
			throw new IllegalArgumentException("rwsi cannot be null");
		}
		if (name == null) {
			if (version == null) {
				return new ObjectIDResolvedWS(rwsi, id);
			} else {
				return new ObjectIDResolvedWS(rwsi, id, version);
			}
		}
		if (version == null) {
			return new ObjectIDResolvedWS(rwsi, name);
		} else {
			return new ObjectIDResolvedWS(rwsi, name, version);
		}
	}
	
	public static ObjectIdentifier create(final WorkspaceIdentifier wsi,
			final String name, final Long id) {
		return create(wsi, name, id, null);
	}
	
	public static ObjectIdentifier create(final WorkspaceIdentifier wsi,
			final String name, final Long id, final Integer ver) {
		xorNameId(name, id, "object");
		if (name != null) {
			if (ver == null) {
				return new ObjectIdentifier(wsi, name);
			}
			return new ObjectIdentifier(wsi, name, ver);
		}
		if (ver == null) {
			return new ObjectIdentifier(wsi, id);
		}
		return new ObjectIdentifier(wsi, id, ver);
	}
	
	public static String createObjectReference(final long workspace,
			final long object, final int version) {
		if (workspace < 1 || object < 1 || version < 1) {
			throw new IllegalArgumentException("All arguments must be > 0");
		}
		return workspace + REFERENCE_SEP + object + REFERENCE_SEP +
				version;
	}
	
	public static ObjectIdentifier parseObjectReference(final String reference) {
		checkString(reference, "reference");
		final String[] r = reference.split(REFERENCE_SEP);
		if (r.length != 2 && r.length != 3) {
			throw new IllegalArgumentException(String.format(
					"Illegal number of separators %s in object reference %s",
					REFERENCE_SEP, reference));
		}
		WorkspaceIdentifier wsi;
		try {
			wsi = new WorkspaceIdentifier(Long.parseLong(r[0]));
		} catch (NumberFormatException nfe) {
			wsi = new WorkspaceIdentifier(r[0]);
		}
		if (r.length == 3) {
			final Integer ver = parseInt(r[2], reference, "version");
			try {
				return new ObjectIdentifier(wsi, Long.parseLong(r[1]), ver);
			} catch (NumberFormatException nfe) {
				return new ObjectIdentifier(wsi, r[1], ver);
			}
		} else {
			try {
				return new ObjectIdentifier(wsi, Long.parseLong(r[1]));
			} catch (NumberFormatException nfe) {
				return new ObjectIdentifier(wsi, r[1]);
			}
		}
	}
	
	private static Integer parseInt(String s, String reference, String portion) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(String.format(
					"Unable to parse %s portion of object reference %s to an integer",
					portion, reference));
		}
	}
	
	@Override
	public String toString() {
		return "ObjectIdentifier [wsi=" + wsi + ", name=" + name + ", id=" + id
				+ ", version=" + version + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((version == null) ? 0 : version.hashCode());
		result = prime * result + ((wsi == null) ? 0 : wsi.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ObjectIdentifier other = (ObjectIdentifier) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (version == null) {
			if (other.version != null)
				return false;
		} else if (!version.equals(other.version))
			return false;
		if (wsi == null) {
			if (other.wsi != null)
				return false;
		} else if (!wsi.equals(other.wsi))
			return false;
		return true;
	}
}
