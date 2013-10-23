package us.kbase.workspace.database;

import us.kbase.typedobj.core.AbsoluteTypeDefId;

public class TypeAndReference {
	
	//TODO return resolved reference instead, ws obj ver all required
	//TODO rename all meta stuff too confusing with real metadata
	
	private AbsoluteTypeDefId type;
	private Reference ref;

	public TypeAndReference(final AbsoluteTypeDefId type, final Reference ref) {
		if (type == null || ref == null) {
			throw new IllegalArgumentException("type nor ref can be null");
		}
		this.type = type;
		this.ref = ref;
	}

	public AbsoluteTypeDefId getType() {
		return type;
	}

	public Reference getReference() {
		return ref;
	}

	@Override
	public String toString() {
		return "TypeAndVersion [type=" + type + ", ref=" + ref + "]";
	}
}
