package us.kbase.workspace.kbase;

import static us.kbase.common.utils.ServiceUtils.checkAddlArgs;
import static us.kbase.workspace.kbase.ArgUtils.getGlobalWSPerm;
import static us.kbase.workspace.kbase.ArgUtils.wsInfoToTuple;
import static us.kbase.workspace.kbase.ArgUtils.processProvenance;
import static us.kbase.workspace.kbase.ArgUtils.longToBoolean;
import static us.kbase.workspace.kbase.ArgUtils.objInfoToTuple;
import static us.kbase.workspace.kbase.ArgUtils.parseDate;
import static us.kbase.workspace.kbase.KBaseIdentifierFactory.processWorkspaceIdentifier;
import static us.kbase.workspace.kbase.KBasePermissions.translatePermission;

import java.io.IOException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import us.kbase.auth.AuthException;
import us.kbase.auth.AuthToken;
import us.kbase.auth.ConfigurableAuthService;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.Tuple9;
import us.kbase.typedobj.core.TypeDefId;
import us.kbase.typedobj.exceptions.NoSuchPrivilegeException;
import us.kbase.typedobj.exceptions.TypeStorageException;
import us.kbase.typedobj.exceptions.TypedObjectSchemaException;
import us.kbase.typedobj.exceptions.TypedObjectValidationException;
import us.kbase.typedobj.idref.IdReferenceHandlerSetFactory;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.ExternalDataUnit;
import us.kbase.workspace.GrantModuleOwnershipParams;
import us.kbase.workspace.ListWorkspaceInfoParams;
import us.kbase.workspace.ObjectSaveData;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.RemoveModuleOwnershipParams;
import us.kbase.workspace.SaveObjectsParams;
import us.kbase.workspace.SetGlobalPermissionsParams;
import us.kbase.workspace.SetPermissionsParams;
import us.kbase.workspace.WorkspaceIdentity;
import us.kbase.workspace.WorkspacePermissions;
import us.kbase.workspace.database.ObjectIDNoWSNoVer;
import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.Permission;
import us.kbase.workspace.database.Provenance;
import us.kbase.workspace.database.User;
import us.kbase.workspace.database.Workspace;
import us.kbase.workspace.database.WorkspaceIdentifier;
import us.kbase.workspace.database.WorkspaceInformation;
import us.kbase.workspace.database.WorkspaceSaveObject;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.database.exceptions.CorruptWorkspaceDBException;
import us.kbase.workspace.database.exceptions.NoSuchObjectException;
import us.kbase.workspace.database.exceptions.NoSuchWorkspaceException;
import us.kbase.workspace.database.exceptions.PreExistingWorkspaceException;
import us.kbase.workspace.database.exceptions.WorkspaceCommunicationException;
import us.kbase.workspace.exceptions.WorkspaceAuthorizationException;

public class WorkspaceServerMethods {
	
	final private Workspace ws;
	final private URL handleServiceUrl;
	final private int maximumIDCount;
	final private ConfigurableAuthService auth;
	
	public WorkspaceServerMethods(
			final Workspace ws,
			final URL handleServiceUrl,
			final int maximumIDCount,
			final ConfigurableAuthService auth) {
		this.ws = ws;
		this.handleServiceUrl = handleServiceUrl;
		this.maximumIDCount = maximumIDCount;
		this.auth = auth;
	}

	public Tuple9<Long, String, String, String, Long, String, String, String, Map<String, String>>
			createWorkspace(
			final CreateWorkspaceParams params, final WorkspaceUser user)
			throws PreExistingWorkspaceException,
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		Permission p = getGlobalWSPerm(params.getGlobalread());
		final WorkspaceInformation meta = ws.createWorkspace(user,
				params.getWorkspace(), p.equals(Permission.READ),
				params.getDescription(), params.getMeta());
		return wsInfoToTuple(meta);
	}
	
	public void setPermissions(final SetPermissionsParams params,
			final WorkspaceUser user)
			throws IOException, AuthException, CorruptWorkspaceDBException,
			NoSuchWorkspaceException, WorkspaceAuthorizationException,
			WorkspaceCommunicationException {
		setPermissions(params, user, false);
	}
	
	public void setPermissions(final SetPermissionsParams params,
			final WorkspaceUser user, boolean asAdmin)
			throws IOException, AuthException, CorruptWorkspaceDBException,
			NoSuchWorkspaceException, WorkspaceAuthorizationException,
			WorkspaceCommunicationException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		final Permission p = translatePermission(params.getNewPermission());
		if (params.getUsers().size() == 0) {
			throw new IllegalArgumentException("Must provide at least one user");
		}
		final List<WorkspaceUser> users = validateUsers(params.getUsers());
		ws.setPermissions(user, wsi, users, p, asAdmin);
	}
	
	public List<WorkspaceUser> validateUsers(final List<String> users)
			throws IOException, AuthException {
		final List<WorkspaceUser> wsusers = ArgUtils.convertUsers(users);
		final Map<String, Boolean> userok;
		try {
			userok = auth.isValidUserName(users);
		} catch (UnknownHostException uhe) {
			//message from UHE is only the host name
			throw new AuthException(
					"Could not contact Authorization Service host to validate user names: "
							+ uhe.getMessage(), uhe);
		}
		for (String u: userok.keySet()) {
			if (!userok.get(u)) {
				throw new IllegalArgumentException(String.format(
						"User %s is not a valid user", u));
			}
		}
		return wsusers;
	}

	public void setGlobalPermission(final SetGlobalPermissionsParams params,
			WorkspaceUser user)
			throws CorruptWorkspaceDBException, NoSuchWorkspaceException,
			WorkspaceAuthorizationException, WorkspaceCommunicationException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		final Permission p = translatePermission(params.getNewPermission());
		ws.setGlobalPermission(user, wsi, p);
	}
	
	public Map<String, String> getPermissions(WorkspaceIdentity wsi,
			WorkspaceUser user)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		return getPermissions(Arrays.asList(wsi), user).getPerms().get(0);
	}
	
	//TODO NOW test this method
	public WorkspacePermissions getPermissions(
			List<WorkspaceIdentity> workspaces, WorkspaceUser user)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		
		final List<WorkspaceIdentifier> wsil =
				new LinkedList<WorkspaceIdentifier>();
		for (final WorkspaceIdentity wsi: workspaces) {
			wsil.add(processWorkspaceIdentifier(wsi));
		}
		final List<Map<User, Permission>> perms = ws.getPermissions(user, wsil);
		final List<Map<String, String>> ret =
				new LinkedList<Map<String,String>>();
		for (final Map<User, Permission> acls: perms){
			final Map<String, String> inner = new HashMap<String, String>();
			for (User acl: acls.keySet()) {
				inner.put(acl.getUser(), translatePermission(acls.get(acl)));
			}
			ret.add(inner);
		}
		return new WorkspacePermissions().withPerms(ret);
	}

	public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String, String>>> saveObjects(
			final SaveObjectsParams params,
			final WorkspaceUser user,
			final AuthToken token)
			throws ParseException, WorkspaceCommunicationException,
			WorkspaceAuthorizationException, NoSuchObjectException,
			CorruptWorkspaceDBException, NoSuchWorkspaceException,
			TypedObjectValidationException, TypeStorageException,
			IOException, TypedObjectSchemaException {

		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		final List<WorkspaceSaveObject> woc = new ArrayList<WorkspaceSaveObject>();
		int count = 1;
		if (params.getObjects().isEmpty()) {
			throw new IllegalArgumentException("No data provided");
		}
		for (ObjectSaveData d: params.getObjects()) {
			checkAddlArgs(d.getAdditionalProperties(), d.getClass());
			ObjectIDNoWSNoVer oi = null;
			if (d.getName() != null || d.getObjid() != null) {
				 oi = ObjectIDNoWSNoVer.create(d.getName(), d.getObjid());
			}
			String errprefix = "Object ";
			if (oi == null) {
				errprefix += count;
			} else {
				errprefix += count + ", " + oi.getIdentifierString() + ",";
			}
			if (d.getData() == null) {
				throw new IllegalArgumentException(errprefix + " has no data");
			}
			TypeDefId t;
			try {
				t = TypeDefId.fromTypeString(d.getType());
			} catch (IllegalArgumentException iae) {
				throw new IllegalArgumentException(errprefix + " type error: "
						+ iae.getLocalizedMessage(), iae);
			}
			final Provenance p = processProvenance(user,
					d.getProvenance());
			final boolean hidden = longToBoolean(d.getHidden());
			try {
				if (oi == null) {
					woc.add(new WorkspaceSaveObject(d.getData(),
							t, d.getMeta(), p, hidden));
				} else {
					woc.add(new WorkspaceSaveObject(oi,
							d.getData(), t, d.getMeta(), p,
							hidden));
				}
			} catch (IllegalArgumentException iae) {
				throw new IllegalArgumentException(errprefix + " save error: "
						+ iae.getLocalizedMessage(), iae);
			}
			count++;
		}
		params.setObjects(null); 
		final IdReferenceHandlerSetFactory fac =
				new IdReferenceHandlerSetFactory(maximumIDCount);
		fac.addFactory(new HandleIdHandlerFactory(handleServiceUrl,
				token));
		
		final List<ObjectInformation> meta = ws.saveObjects(user, wsi, woc, fac); 
		return objInfoToTuple(meta, true);
	}
	
	public void grantModuleOwnership(final GrantModuleOwnershipParams params,
			final WorkspaceUser user, boolean asAdmin)
			throws TypeStorageException, NoSuchPrivilegeException {
		checkAddlArgs(params.getAdditionalProperties(),
				GrantModuleOwnershipParams.class);
		ws.grantModuleOwnership(params.getMod(), params.getNewOwner(),
				longToBoolean(params.getWithGrantOption()), user, asAdmin);
	}

	public void removeModuleOwnership(final RemoveModuleOwnershipParams params,
			final WorkspaceUser user, final boolean asAdmin)
			throws NoSuchPrivilegeException, TypeStorageException {
		checkAddlArgs(params.getAdditionalProperties(),
				RemoveModuleOwnershipParams.class);
		ws.removeModuleOwnership(params.getMod(), params.getOldOwner(),
				user, asAdmin);
	}

	public List<Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>>>
			listWorkspaceInfo(final ListWorkspaceInfoParams params,
			final WorkspaceUser user)
			throws WorkspaceCommunicationException, CorruptWorkspaceDBException, ParseException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final Permission p = params.getPerm() == null ? null :
				translatePermission(params.getPerm());
		return wsInfoToTuple(ws.listWorkspaces(user,
				p, ArgUtils.convertUsers(params.getOwners()), params.getMeta(),
				parseDate(params.getAfter()),
				parseDate(params.getBefore()),
				longToBoolean(params.getExcludeGlobal()),
				longToBoolean(params.getShowDeleted()),
				longToBoolean(params.getShowOnlyDeleted())));
	}

	/* would do this more gracefully in actual code. Probably make an interface
	 * for each of the api types; the methods here take the interface so each
	 * API would need to wrap its type in a class that implements the interface.
	 * For this POC code just doing the dirty.
	 * 
	 * Alternatively, just make an adapter class for each api that translates
	 * the API classes for each function and don't have these methods
	 * know about any of the api classes as they do now.
	 * 
	 * Also, the reason for this class is so the administration class
	 * can call the contained methods. Just make the administration class
	 * use the most recent API.
	 */
	public void grantModuleOwnership(
			us.kbase.workspace.api.v1.workspace.GrantModuleOwnershipParams params,
			WorkspaceUser user, boolean asAdmin)
			throws TypeStorageException, NoSuchPrivilegeException {
		grantModuleOwnership(new GrantModuleOwnershipParams()
				.withMod(params.getMod())
				.withNewOwner(params.getNewOwner())
				.withWithGrantOption(params.getWithGrantOption()),
				user, asAdmin);
		
	}
	
	public void removeModuleOwnership(
			us.kbase.workspace.api.v1.workspace.RemoveModuleOwnershipParams params,
			WorkspaceUser user, boolean asAdmin)
			throws NoSuchPrivilegeException, TypeStorageException {
		removeModuleOwnership(new RemoveModuleOwnershipParams()
			.withMod(params.getMod())
			.withOldOwner(params.getOldOwner()),
			user, asAdmin);
		
	}

	public Tuple9<Long, String, String, String, Long, String, String, String, Map<String, String>> createWorkspace(
			us.kbase.workspace.api.v1.workspace.CreateWorkspaceParams params,
			WorkspaceUser user)
			throws PreExistingWorkspaceException,
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		return createWorkspace(new CreateWorkspaceParams()
				.withDescription(params.getDescription())
				.withGlobalread(params.getGlobalread())
				.withMeta(params.getMeta())
				.withWorkspace(params.getWorkspace()),
				user);
	}

	public void setPermissions(
			us.kbase.workspace.api.v1.workspace.SetPermissionsParams params,
			WorkspaceUser user)
			throws CorruptWorkspaceDBException, NoSuchWorkspaceException,
			WorkspaceCommunicationException, WorkspaceAuthorizationException,
			IOException, AuthException {
		setPermissions(new SetPermissionsParams()
				.withId(params.getId())
				.withNewPermission(params.getNewPermission())
				.withUsers(params.getUsers())
				.withWorkspace(params.getWorkspace()),
				user);
		
	}

	public void setGlobalPermission(
			us.kbase.workspace.api.v1.workspace.SetGlobalPermissionsParams params,
			WorkspaceUser user)
			throws CorruptWorkspaceDBException, NoSuchWorkspaceException,
			WorkspaceCommunicationException, WorkspaceAuthorizationException {
		setGlobalPermission(new SetGlobalPermissionsParams()
			.withId(params.getId())
			.withNewPermission(params.getNewPermission())
			.withWorkspace(params.getWorkspace()),
			user);
	}

	public us.kbase.workspace.api.v1.workspace.WorkspacePermissions getPermissions(
			us.kbase.workspace.api.v1.workspace.GetPermissionsMassParams mass, WorkspaceUser user)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		final List<WorkspaceIdentity> wsis = new LinkedList<WorkspaceIdentity>();
		for (final us.kbase.workspace.api.v1.workspace.WorkspaceIdentity w:
				mass.getWorkspaces()) {
			wsis.add(new WorkspaceIdentity()
					.withId(w.getId())
					.withWorkspace(w.getWorkspace()));
		}
		final WorkspacePermissions p = getPermissions(wsis, user);
		return new us.kbase.workspace.api.v1.workspace.WorkspacePermissions()
				.withPerms(p.getPerms());
	}

	public Map<String, String> getPermissions(
			us.kbase.workspace.api.v1.workspace.WorkspaceIdentity wsi,
			WorkspaceUser user)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		return getPermissions(new WorkspaceIdentity()
				.withId(wsi.getId())
				.withWorkspace(wsi.getWorkspace()),
				user);
	}

	public List<Tuple9<Long, String, String, String, Long, String, String, String, Map<String, String>>> listWorkspaceInfo(
			us.kbase.workspace.api.v1.workspace.ListWorkspaceInfoParams params,
			WorkspaceUser user)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException, ParseException {
		return listWorkspaceInfo(new ListWorkspaceInfoParams()
				.withAfter(params.getAfter())
				.withBefore(params.getBefore())
				.withExcludeGlobal(params.getExcludeGlobal())
				.withMeta(params.getMeta())
				.withOwners(params.getOwners())
				.withPerm(params.getPerm())
				.withShowDeleted(params.getShowDeleted())
				.withShowOnlyDeleted(params.getShowOnlyDeleted()), user);
	}

	public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String, String>>> saveObjects(
			us.kbase.workspace.api.v1.workspace.SaveObjectsParams params,
			WorkspaceUser user, AuthToken authPart)
			throws NoSuchObjectException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException, NoSuchWorkspaceException,
			WorkspaceAuthorizationException, TypedObjectValidationException,
			TypeStorageException, TypedObjectSchemaException, ParseException,
			IOException {
		return saveObjects(new SaveObjectsParams()
				.withId(params.getId())
				.withObjects(convertObjectSaveData(params.getObjects()))
				.withWorkspace(params.getWorkspace()), user, authPart);
	}

	private List<ObjectSaveData> convertObjectSaveData(
			List<us.kbase.workspace.api.v1.workspace.ObjectSaveData> objects) {
		final List<ObjectSaveData> osd = new LinkedList<ObjectSaveData>();
		for (final us.kbase.workspace.api.v1.workspace.ObjectSaveData o: objects) {
			osd.add(new ObjectSaveData()
					.withData(o.getData())
					.withHidden(o.getHidden())
					.withMeta(o.getMeta())
					.withName(o.getName())
					.withObjid(o.getObjid())
					.withProvenance(convertProvenance(o.getProvenance()))
					.withType(o.getType()));
		}
		return osd;
	}

	
	private List<ProvenanceAction> convertProvenance(
			List<us.kbase.workspace.api.v1.workspace.ProvenanceAction> provenance) {
		final List<ProvenanceAction> pa = new LinkedList<ProvenanceAction>();
		for (final us.kbase.workspace.api.v1.workspace.ProvenanceAction p: provenance) {
			pa.add(new ProvenanceAction()
					.withDescription(p.getDescription())
					.withExternalData(convertExternalData(p.getExternalData()))
					.withInputWsObjects(p.getInputWsObjects())
					.withIntermediateIncoming(p.getIntermediateIncoming())
					.withIntermediateOutgoing(p.getIntermediateOutgoing())
					.withMethod(p.getMethod())
					.withMethodParams(p.getMethodParams())
					.withResolvedWsObjects(p.getResolvedWsObjects())
					.withScript(p.getScript())
					.withScriptCommandLine(p.getScriptCommandLine())
					.withScriptVer(p.getScriptVer())
					.withService(p.getService())
					.withServiceVer(p.getServiceVer())
					.withTime(p.getTime()));
		}
		return pa;
	}

	private List<ExternalDataUnit> convertExternalData(
			List<us.kbase.workspace.api.v1.workspace.ExternalDataUnit> externalData) {
		final List<ExternalDataUnit> edu = new LinkedList<ExternalDataUnit>();
		for (final us.kbase.workspace.api.v1.workspace.ExternalDataUnit e: externalData) {
			edu.add(new ExternalDataUnit()
					.withDataId(e.getDataId())
					.withDataUrl(e.getDataUrl())
					.withDescription(e.getDescription())
					.withResourceName(e.getResourceName())
					.withResourceReleaseDate(e.getResourceReleaseDate())
					.withResourceUrl(e.getResourceUrl())
					.withResourceVersion(e.getResourceVersion()));
		}
		return edu;
	}
}
