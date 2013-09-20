package us.kbase.workspace.kbase;

import static us.kbase.workspace.kbase.KBasePermissions.translatePermission;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import us.kbase.common.service.Tuple10;
import us.kbase.common.service.Tuple6;
import us.kbase.common.service.Tuple9;
import us.kbase.common.service.UObject;
import us.kbase.auth.AuthToken;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.database.ObjectMetaData;
import us.kbase.workspace.database.ObjectUserMetaData;
import us.kbase.workspace.database.WorkspaceMetaData;
import us.kbase.workspace.database.WorkspaceObjectData;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.workspaces.Provenance;

public class ArgUtils {
	
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	
	public static void checkAddlArgs(final Map<String, Object> addlargs,
			@SuppressWarnings("rawtypes") final Class clazz) {
		if (addlargs.isEmpty()) {
			return;
		}
		throw new IllegalArgumentException(String.format(
				"Unexpected arguments in %s: %s",
				clazz.getName().substring(clazz.getName().lastIndexOf(".") + 1),
				StringUtils.join(addlargs.keySet(), " ")));
	}

	public static String formatDate(final Date d) {
		if (d == null) {
			return null;
		}
		return DATE_FORMAT.format(d);
	}
	
	public static Provenance processProvenance(final String user,
			final List<ProvenanceAction> actions) {
		
		Provenance p = new Provenance(user);
		if (actions == null) {
			return p;
		}
		for (ProvenanceAction a: actions) {
			checkAddlArgs(a.getAdditionalProperties(), a.getClass());
			Provenance.ProvenanceAction pa = new Provenance.ProvenanceAction();
			if (a.getService() != null) {
				pa = pa.withServiceName(a.getService());
			}
			//TODO remainder of provenance actions
			//TODO parse provenance date 
		}
		
		return p;
	}
	
	public static Tuple6<Integer, String, String, String, String, String>
			wsMetaToTuple (final WorkspaceMetaData meta) {
		return new Tuple6<Integer, String, String, String, String, String>()
				.withE1(meta.getId())
				.withE2(meta.getName())
				.withE3(meta.getOwner().getUser())
				.withE4(formatDate(meta.getModDate()))
				.withE5(translatePermission(meta.getUserPermission())) 
				.withE6(translatePermission(meta.isGloballyReadable()));
	}
	
	public static List<Tuple9<Integer, String, String, String, Integer, String,
			Integer, String, Integer>>
			objMetaToTuple (final List<ObjectMetaData> meta) {
		
		//oh the humanity
		final List<Tuple9<Integer, String, String, String, Integer, String,
			Integer, String, Integer>> ret = 
			new ArrayList<Tuple9<Integer, String, String, String, Integer,
			String, Integer, String, Integer>>();
		
		for (ObjectMetaData m: meta) {
			ret.add(new Tuple9<Integer, String, String, String, Integer,
					String, Integer, String, Integer>()
					.withE1(m.getObjectId())
					.withE2(m.getObjectName())
					.withE3(m.getTypeString())
					.withE4(formatDate(m.getCreatedDate()))
					.withE5(m.getVersion())
					.withE6(m.getCreator().getUser())
					.withE7(m.getWorkspaceId())
					.withE8(m.getCheckSum())
					.withE9(m.getSize()));
		}
		return ret;
}
	
	public static Tuple10<Integer, String, String, String, Integer, String,
			Integer, String, Integer, Map<String, String>>
			objUserMetaToTuple (final ObjectUserMetaData meta) {
		final List<ObjectUserMetaData> m = new ArrayList<ObjectUserMetaData>();
		m.add(meta);
		return objUserMetaToTuple(m).get(0);
	}
	
	public static List<Tuple10<Integer, String, String, String, Integer, String,
			Integer, String, Integer, Map<String, String>>>
			objUserMetaToTuple (final List<ObjectUserMetaData> meta) {
		
		//oh the humanity
		final List<Tuple10<Integer, String, String, String, Integer, String,
			Integer, String, Integer, Map<String, String>>> ret = 
			new ArrayList<Tuple10<Integer, String, String, String, Integer,
			String, Integer, String, Integer, Map<String, String>>>();
		
		for (ObjectUserMetaData m: meta) {
			ret.add(new Tuple10<Integer, String, String, String, Integer,
					String, Integer, String, Integer, Map<String, String>>()
					.withE1(m.getObjectId())
					.withE2(m.getObjectName())
					.withE3(m.getTypeString())
					.withE4(formatDate(m.getCreatedDate()))
					.withE5(m.getVersion())
					.withE6(m.getCreator().getUser())
					.withE7(m.getWorkspaceId())
					.withE8(m.getCheckSum())
					.withE9(m.getSize())
					.withE10(m.getUserMetaData()));
		}
		return ret;
	}
	
	public static WorkspaceUser getUser(final AuthToken token) {
		if (token == null) {
			return null;
		}
		return new WorkspaceUser(token.getUserName());
	}
	
	public static List<ObjectData> translateObjectData(
			final List<WorkspaceObjectData> objects) {
		final List<ObjectData> ret = new ArrayList<ObjectData>();
		for (final WorkspaceObjectData o: objects) {
			ret.add(new ObjectData()
					.withData(new UObject(o.getData()))
					.withMeta(objUserMetaToTuple(o.getMeta())));
		}
		return ret;
	}
}
