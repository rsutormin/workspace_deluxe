package us.kbase.workspace.api.v2.workspace;

import java.util.List;
import java.util.Map;

import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonServerMethod;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.Tuple9;
import us.kbase.common.service.UObject;

//BEGIN_HEADER
import static us.kbase.common.utils.ServiceUtils.checkAddlArgs;
import static us.kbase.workspace.kbase.ArgUtils.getUser;
import static us.kbase.workspace.kbase.ArgUtils.getGlobalWSPerm;
import static us.kbase.workspace.kbase.ArgUtils.wsInfoToTuple;
import static us.kbase.workspace.kbase.ArgUtils.translateObjectProvInfo;
import static us.kbase.workspace.kbase.ArgUtils.translateObjectData;
import static us.kbase.workspace.kbase.ArgUtils.objInfoToTuple;
import static us.kbase.workspace.kbase.ArgUtils.translateObjectDataList;
import static us.kbase.workspace.kbase.ArgUtils.longToBoolean;
import static us.kbase.workspace.kbase.ArgUtils.longToInt;
import static us.kbase.workspace.kbase.ArgUtils.parseDate;
import static us.kbase.workspace.kbase.KBaseIdentifierFactory.processObjectIdentifier;
import static us.kbase.workspace.kbase.KBaseIdentifierFactory.processObjectIdentifiers;
import static us.kbase.workspace.kbase.KBaseIdentifierFactory.processSubObjectIdentifiers;
import static us.kbase.workspace.kbase.KBaseIdentifierFactory.processWorkspaceIdentifier;
import static us.kbase.workspace.kbase.KBasePermissions.translatePermission;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

//import org.apache.commons.lang3.builder.ToStringBuilder;

import us.kbase.auth.RefreshingToken;
import us.kbase.typedobj.core.AbsoluteTypeDefId;
import us.kbase.typedobj.core.TempFilesManager;
import us.kbase.typedobj.core.TypeDefId;
import us.kbase.typedobj.core.TypeDefName;
import us.kbase.typedobj.db.FuncDetailedInfo;
import us.kbase.typedobj.db.ModuleDefId;
import us.kbase.typedobj.db.TypeChange;
import us.kbase.typedobj.db.TypeDetailedInfo;
import us.kbase.workspace.database.ByteArrayFileCacheManager.ByteArrayFileCache;
import us.kbase.workspace.database.ResourceUsageConfigurationBuilder.ResourceUsageConfiguration;
import us.kbase.workspace.database.ObjectChain;
import us.kbase.workspace.database.SubObjectIdentifier;
import us.kbase.workspace.database.Workspace;
import us.kbase.workspace.database.ObjectIdentifier;
import us.kbase.workspace.database.Permission;
import us.kbase.workspace.database.WorkspaceIdentifier;
import us.kbase.workspace.database.WorkspaceInformation;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.kbase.ArgUtils;
import us.kbase.workspace.kbase.InitWorkspaceServer.InitReporter;
import us.kbase.workspace.kbase.InitWorkspaceServer;
import us.kbase.workspace.kbase.InitWorkspaceServer.WorkspaceInitResults;
import us.kbase.workspace.kbase.KBaseWorkspaceConfig;
import us.kbase.workspace.kbase.WorkspaceAdministration;
import us.kbase.workspace.kbase.WorkspaceServerMethods;
//END_HEADER

/**
 * <p>Original spec-file module name: Workspace</p>
 * <pre>
 * The Workspace Service (WSS) is primarily a language independent remote storage
 * and retrieval system for KBase typed objects (TO) defined with the KBase
 * Interface Description Language (KIDL). It has the following primary features:
 * - Immutable storage of TOs with
 *         - user defined metadata 
 *         - data provenance
 * - Versioning of TOs
 * - Referencing from TO to TO
 * - Typechecking of all saved objects against a KIDL specification
 * - Collecting typed objects into a workspace
 * - Sharing workspaces with specific KBase users or the world
 * - Freezing and publishing workspaces
 * Size limits:
 * TOs are limited to 1GB
 * TO subdata is limited to 15MB
 * TO provenance is limited to 1MB
 * User provided metadata for workspaces and objects is limited to 16kB
 * NOTE ON BINARY DATA:
 * All binary data must be hex encoded prior to storage in a workspace. 
 * Attempting to send binary data via a workspace client will cause errors.
 * </pre>
 */
public class WorkspaceServer extends JsonServerServlet {
    private static final long serialVersionUID = 1L;

    //BEGIN_CLASS_HEADER
	//TODO java doc - really low priority, sorry
    //TODO timestamps for startup script
    //TODO check shock version
    //TODO shock client should ignore extra fields
	
	private static final String VER = "0.3.5";

	private static final long MAX_RPC_PACKAGE_SIZE = 1005000000;
	private static final int MAX_RPC_PACKAGE_MEM_USE = 100000000;
	
	private static Map<String, String> wsConfig = null;
	
	private final TempFilesManager tfm;
	private final Workspace ws;
	private final WorkspaceServerMethods wsmeth;
	private final WorkspaceAdministration wsadmin;
	
	private final URL handleManagerUrl;
	private final RefreshingToken handleMgrToken;
	
	private ThreadLocal<Set<ByteArrayFileCache>> resourcesToDelete =
			new ThreadLocal<Set<ByteArrayFileCache>>();
	
	
	public static void clearConfigForTests() {
		wsConfig = null;
	}
	
	@Override
	protected File generateTempFile() {
		return ws.getTempFilesManager().generateTempFile("rpc", "json");
	}
	
	public TempFilesManager getTempFilesManager() {
		return tfm;
	}

	@Override
	protected void onRpcMethodDone() {
		if (resourcesToDelete.get() != null &&
				!resourcesToDelete.get().isEmpty()) {
			for (final ByteArrayFileCache f : resourcesToDelete.get())
				try {
					f.destroy();
				} catch (Exception ignore) {}
			resourcesToDelete.set(null);
		}
	}
	
	public ResourceUsageConfiguration getWorkspaceResourceUsageConfig() {
		return ws.getResourceConfig();
	}
	
	public void setResourceUsageConfiguration(
			final ResourceUsageConfiguration cfg) {
		ws.setResourceConfig(cfg);
	}
	
	public void setUpLogger() {
		final Logger rootLogger = ((Logger) LoggerFactory.getLogger(
				org.slf4j.Logger.ROOT_LOGGER_NAME));
		rootLogger.setLevel(Level.OFF);
		rootLogger.detachAndStopAllAppenders();
		final Logger kbaseRootLogger = (Logger) LoggerFactory.getLogger(
				"us.kbase");
		//would be better to also set the level here on calls to the server
		//setLogLevel, but meh for now
		kbaseRootLogger.setLevel(Level.ALL);
		final AppenderBase<ILoggingEvent> kbaseAppender =
				new AppenderBase<ILoggingEvent>() {

			@Override
			protected void append(final ILoggingEvent event) {
				//for now only INFO is tested; test others as they're needed
				final Level l = event.getLevel();
				if (l.equals(Level.TRACE)) {
					logDebug(event.getFormattedMessage(), 3);
				} else if (l.equals(Level.DEBUG)) {
					logDebug(event.getFormattedMessage());
				} else if (l.equals(Level.INFO) || l.equals(Level.WARN)) {
					logInfo(event.getFormattedMessage());
				} else if (l.equals(Level.ERROR)) {
					logErr(event.getFormattedMessage());
				}
			}
		};
		kbaseAppender.start();
		kbaseRootLogger.addAppender(kbaseAppender);
	}
	
	private class WorkspaceInitReporter extends InitReporter {

		@Override
		public void reportInfo(final String info) {
			logInfo(info);
			System.out.println(info);
		}

		@Override
		public void handleFail(final String fail) {
			logErr(fail);
			System.out.println(fail);
			startupFailed();
		}
		
	}
	
	/* would need to think about a better solution for the following methods.
	 * Ideally make sure no other classes are exposed to the typecompiler
	 * classes at all and all conversion to necessary classes happens in the
	 * API version specific files.
	 */
	private List<us.kbase.workspace.ObjectIdentity>
			convertObjectIds(List<ObjectIdentity> objectIds) {
		final List<us.kbase.workspace.ObjectIdentity> o =
				new LinkedList<us.kbase.workspace.ObjectIdentity>();
		for (final ObjectIdentity oi: objectIds) {
			o.add(new us.kbase.workspace.ObjectIdentity()
					.withName(oi.getName())
					.withObjid(oi.getObjid())
					.withRef(oi.getRef())
					.withVer(oi.getVer())
					.withWorkspace(oi.getWorkspace())
					.withWsid(oi.getWsid()));
		}
		return o;
	}

	private us.kbase.workspace.ObjectIdentity convertObjectId(
			ObjectIdentity object) {
		return convertObjectIds(Arrays.asList(object)).get(0);
	}
	
	private List<us.kbase.workspace.SubObjectIdentity> convertSubObjectIdentifers(
			List<SubObjectIdentity> subObjectIds) {
		final List<us.kbase.workspace.SubObjectIdentity> sub =
				new LinkedList<us.kbase.workspace.SubObjectIdentity>();
		for (final SubObjectIdentity s: subObjectIds) {
			sub.add(new us.kbase.workspace.SubObjectIdentity()
					.withIncluded(s.getIncluded())
					.withStrictArrays(s.getStrictArrays())
					.withStrictMaps(s.getStrictMaps())
					.withName(s.getName())
					.withObjid(s.getObjid())
					.withRef(s.getRef())
					.withVer(s.getVer())
					.withWorkspace(s.getWorkspace())
					.withWsid(s.getWsid()));
		}
		
		return sub;
	}
	
	private List<ObjectData> convertObjectData(
			List<us.kbase.workspace.ObjectData> translateObjectData) {
		final List<ObjectData> od = new LinkedList<ObjectData>();
		for (final us.kbase.workspace.ObjectData o: translateObjectData) {
			od.add(new ObjectData()
					.withData(o.getData())
					.withCopied(o.getCopied())
					.withCopySourceInaccessible(o.getCopySourceInaccessible())
					.withCreated(o.getCreated())
					.withCreator(o.getCreator())
					.withExtractedIds(o.getExtractedIds())
					.withHandleError(o.getHandleError())
					.withHandleStacktrace(o.getHandleStacktrace())
					.withInfo(o.getInfo())
					.withProvenance(convertProvenance(o.getProvenance()))
					.withRefs(o.getRefs()));
		}
		return od;
	}
	
	private List<ObjectProvenanceInfo> convertProvInfo(
			List<us.kbase.workspace.ObjectProvenanceInfo> p) {
		final List<ObjectProvenanceInfo> opi =
				new LinkedList<ObjectProvenanceInfo>();
		for (final us.kbase.workspace.ObjectProvenanceInfo o: p) {
			opi.add(new ObjectProvenanceInfo()
				.withCopied(o.getCopied())
				.withCopySourceInaccessible(o.getCopySourceInaccessible())
				.withCreated(o.getCreated())
				.withCreator(o.getCreator())
				.withExtractedIds(o.getExtractedIds())
				.withHandleError(o.getHandleError())
				.withHandleStacktrace(o.getHandleStacktrace())
				.withInfo(o.getInfo())
				.withProvenance(convertProvenance(o.getProvenance()))
				.withRefs(o.getRefs()));
		}
		return opi;
	}

	private List<ProvenanceAction> convertProvenance(
			List<us.kbase.workspace.ProvenanceAction> provenance) {
		final List<ProvenanceAction> pa = new LinkedList<ProvenanceAction>();
		for (final us.kbase.workspace.ProvenanceAction p: provenance) {
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
			List<us.kbase.workspace.ExternalDataUnit> externalData) {
		final List<ExternalDataUnit> edu = new LinkedList<ExternalDataUnit>();
		for (final us.kbase.workspace.ExternalDataUnit e: externalData) {
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
	
	private List<us.kbase.workspace.ObjectSaveData> convertObjectSaveData(
			List<ObjectSaveData> objects) {
		final List<us.kbase.workspace.ObjectSaveData> osd = new LinkedList<us.kbase.workspace.ObjectSaveData>();
		for (final ObjectSaveData o: objects) {
			osd.add(new us.kbase.workspace.ObjectSaveData()
					.withData(o.getData())
					.withHidden(o.getHidden())
					.withMeta(o.getMeta())
					.withName(o.getName())
					.withObjid(o.getObjid())
					.withProvenance(convertToProvenance(o.getProvenance()))
					.withType(o.getType()));
		}
		return osd;
	}

	
	private List<us.kbase.workspace.ProvenanceAction> convertToProvenance(
			List<ProvenanceAction> provenance) {
		final List<us.kbase.workspace.ProvenanceAction> pa =
				new LinkedList<us.kbase.workspace.ProvenanceAction>();
		for (final ProvenanceAction p: provenance) {
			pa.add(new us.kbase.workspace.ProvenanceAction()
					.withDescription(p.getDescription())
					.withExternalData(convertToExternalData(p.getExternalData()))
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

	private List<us.kbase.workspace.ExternalDataUnit> convertToExternalData(
			List<ExternalDataUnit> externalData) {
		final List<us.kbase.workspace.ExternalDataUnit> edu =
				new LinkedList<us.kbase.workspace.ExternalDataUnit>();
		for (final ExternalDataUnit e: externalData) {
			edu.add(new us.kbase.workspace.ExternalDataUnit()
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
	
    //END_CLASS_HEADER

    public WorkspaceServer() throws Exception {
        super("Workspace");
        //BEGIN_CONSTRUCTOR
		setUpLogger();
		setMaxRPCPackageSize(MAX_RPC_PACKAGE_SIZE);
		setMaxRpcMemoryCacheSize(MAX_RPC_PACKAGE_MEM_USE);
		//assign config once per jvm, otherwise you could wind up with
		//different threads talking to different mongo instances
		//E.g. first thread's config applies to all threads.
		if (wsConfig == null) {
			wsConfig = new HashMap<String, String>();
			wsConfig.putAll(super.config);
		}
		
		final KBaseWorkspaceConfig cfg = new KBaseWorkspaceConfig(wsConfig);
		for (final String info: cfg.getInfoMessages()) {
			logInfo(info);
			System.out.println(info);
		}
		for (final String error: cfg.getErrors()) {
			logErr(error);
			System.out.println(error);
		}
		
		TempFilesManager tfm = null;
		Workspace ws = null;
		WorkspaceServerMethods wsmeth = null;
		WorkspaceAdministration wsadmin = null;
		URL handleManagerUrl = null;
		RefreshingToken handleMgrToken = null;
		
		if (cfg.hasErrors()) {
			logErr("Workspace server configuration has errors - all calls will fail");
			System.out.println(
					"Workspace server configuration has errors - all calls will fail");
			startupFailed();
		} else {

			final WorkspaceInitReporter rep = new WorkspaceInitReporter();
			final WorkspaceInitResults res =
					InitWorkspaceServer.initWorkspaceServer(cfg, rep);

			if (!rep.isFailed()) {
				tfm = res.getTempFilesManager();
				ws = res.getWs();
				wsmeth = res.getWsmeth();
				wsadmin = res.getWsAdmin();
				handleManagerUrl = res.getHandleManagerUrl();
				handleMgrToken = res.getHandleMgrToken();
				setRpcDiskCacheTempDir(tfm.getTempDir());
			}
		}
		this.tfm = tfm;
		this.ws = ws;
		this.wsmeth = wsmeth;
		this.wsadmin = wsadmin;
		this.handleManagerUrl = handleManagerUrl;
		this.handleMgrToken = handleMgrToken;
        //END_CONSTRUCTOR
    }

    /**
     * <p>Original spec-file function name: ver</p>
     * <pre>
     * Returns the version of the workspace service.
     * </pre>
     * @return   parameter "ver" of String
     */
    @JsonServerMethod(rpc = "Workspace.ver")
    public String ver() throws Exception {
        String returnVal = null;
        //BEGIN ver
		returnVal = VER + " API2";
        //END ver
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: a_v2_function</p>
     * <pre>
     * </pre>
     * @return   parameter "importantdata" of String
     */
    @JsonServerMethod(rpc = "Workspace.a_v2_function")
    public String aV2Function() throws Exception {
        String returnVal = null;
        //BEGIN a_v2_function
        returnVal = "Good heavens, an alternate API in the same service!";
        //END a_v2_function
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: create_workspace</p>
     * <pre>
     * Creates a new workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.CreateWorkspaceParams CreateWorkspaceParams}
     * @return   parameter "info" of original type "workspace_info" (Information about a workspace. ws_id id - the numerical ID of the workspace. ws_name workspace - name of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. int objects - the approximate number of objects currently stored in the workspace. permission user_permission - permissions for the authenticated user of this workspace. permission globalread - whether this workspace is globally readable. lock_status lockstat - the status of the workspace lock. usermeta metadata - arbitrary user-supplied metadata about the workspace.) &rarr; tuple of size 9: parameter "id" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "owner" of original type "username" (Login name of a KBase user account.), parameter "moddate" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "object" of Long, parameter "user_permission" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "globalread" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "lockstat" of original type "lock_status" (The lock status of a workspace. One of 'unlocked', 'locked', or 'published'.), parameter "metadata" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.create_workspace")
    public Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> createWorkspace(CreateWorkspaceParams params, AuthToken authPart) throws Exception {
        Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> returnVal = null;
        //BEGIN create_workspace
		returnVal = wsmeth.createWorkspace(params, getUser(authPart));
        //END create_workspace
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: alter_workspace_metadata</p>
     * <pre>
     * Change the metadata associated with a workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.AlterWorkspaceMetadataParams AlterWorkspaceMetadataParams}
     */
    @JsonServerMethod(rpc = "Workspace.alter_workspace_metadata")
    public void alterWorkspaceMetadata(AlterWorkspaceMetadataParams params, AuthToken authPart) throws Exception {
        //BEGIN alter_workspace_metadata
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		if (params.getNew() == null && params.getRemove() == null) {
			throw new IllegalArgumentException(
					"The new and remove params cannot both be null");
		}
		final WorkspaceIdentifier wsi =
				processWorkspaceIdentifier(params.getWsi());
		final WorkspaceUser user = getUser(authPart);
		if (params.getRemove() != null) {
			for (final String key: params.getRemove()) {
				ws.removeWorkspaceMetadata(user, wsi, key);
			}
		}
		if (params.getNew() != null) {
			ws.setWorkspaceMetadata(user, wsi, params.getNew());
		}
        //END alter_workspace_metadata
    }


	/**
     * <p>Original spec-file function name: clone_workspace</p>
     * <pre>
     * Clones a workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.CloneWorkspaceParams CloneWorkspaceParams}
     * @return   parameter "info" of original type "workspace_info" (Information about a workspace. ws_id id - the numerical ID of the workspace. ws_name workspace - name of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. int objects - the approximate number of objects currently stored in the workspace. permission user_permission - permissions for the authenticated user of this workspace. permission globalread - whether this workspace is globally readable. lock_status lockstat - the status of the workspace lock. usermeta metadata - arbitrary user-supplied metadata about the workspace.) &rarr; tuple of size 9: parameter "id" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "owner" of original type "username" (Login name of a KBase user account.), parameter "moddate" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "object" of Long, parameter "user_permission" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "globalread" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "lockstat" of original type "lock_status" (The lock status of a workspace. One of 'unlocked', 'locked', or 'published'.), parameter "metadata" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.clone_workspace")
    public Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> cloneWorkspace(CloneWorkspaceParams params, AuthToken authPart) throws Exception {
        Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> returnVal = null;
        //BEGIN clone_workspace
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		Permission p = getGlobalWSPerm(params.getGlobalread());
		final WorkspaceIdentifier wsi =
				processWorkspaceIdentifier(params.getWsi());
		final WorkspaceInformation meta = ws.cloneWorkspace(getUser(authPart),
				wsi, params.getWorkspace(), p.equals(Permission.READ),
				params.getDescription(), params.getMeta());
		returnVal = wsInfoToTuple(meta);
        //END clone_workspace
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: lock_workspace</p>
     * <pre>
     * Lock a workspace, preventing further changes.
     *         WARNING: Locking a workspace is permanent. A workspace, once locked,
     *         cannot be unlocked.
     *         
     *         The only changes allowed for a locked workspace are changing user
     *         based permissions or making a private workspace globally readable,
     *         thus permanently publishing the workspace. A locked, globally readable
     *         workspace cannot be made private.
     * </pre>
     * @param   wsi   instance of type {@link us.kbase.workspace.api.v2.workspace.WorkspaceIdentity WorkspaceIdentity}
     * @return   parameter "info" of original type "workspace_info" (Information about a workspace. ws_id id - the numerical ID of the workspace. ws_name workspace - name of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. int objects - the approximate number of objects currently stored in the workspace. permission user_permission - permissions for the authenticated user of this workspace. permission globalread - whether this workspace is globally readable. lock_status lockstat - the status of the workspace lock. usermeta metadata - arbitrary user-supplied metadata about the workspace.) &rarr; tuple of size 9: parameter "id" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "owner" of original type "username" (Login name of a KBase user account.), parameter "moddate" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "object" of Long, parameter "user_permission" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "globalread" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "lockstat" of original type "lock_status" (The lock status of a workspace. One of 'unlocked', 'locked', or 'published'.), parameter "metadata" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.lock_workspace")
    public Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> lockWorkspace(WorkspaceIdentity wsi, AuthToken authPart) throws Exception {
        Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> returnVal = null;
        //BEGIN lock_workspace
		final WorkspaceIdentifier wsid = processWorkspaceIdentifier(wsi);
		returnVal = wsInfoToTuple(ws.lockWorkspace(getUser(authPart), wsid));
        //END lock_workspace
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_workspace_info</p>
     * <pre>
     * Get information associated with a workspace.
     * </pre>
     * @param   wsi   instance of type {@link us.kbase.workspace.api.v2.workspace.WorkspaceIdentity WorkspaceIdentity}
     * @return   parameter "info" of original type "workspace_info" (Information about a workspace. ws_id id - the numerical ID of the workspace. ws_name workspace - name of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. int objects - the approximate number of objects currently stored in the workspace. permission user_permission - permissions for the authenticated user of this workspace. permission globalread - whether this workspace is globally readable. lock_status lockstat - the status of the workspace lock. usermeta metadata - arbitrary user-supplied metadata about the workspace.) &rarr; tuple of size 9: parameter "id" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "owner" of original type "username" (Login name of a KBase user account.), parameter "moddate" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "object" of Long, parameter "user_permission" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "globalread" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "lockstat" of original type "lock_status" (The lock status of a workspace. One of 'unlocked', 'locked', or 'published'.), parameter "metadata" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.get_workspace_info", authOptional=true)
    public Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> getWorkspaceInfo(WorkspaceIdentity wsi, AuthToken authPart) throws Exception {
        Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> returnVal = null;
        //BEGIN get_workspace_info
		final WorkspaceIdentifier wksp = processWorkspaceIdentifier(wsi);
		final WorkspaceInformation meta = ws.getWorkspaceInformation(
				getUser(authPart), wksp);
		returnVal = wsInfoToTuple(meta);
        //END get_workspace_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_workspace_description</p>
     * <pre>
     * Get a workspace's description.
     * </pre>
     * @param   wsi   instance of type {@link us.kbase.workspace.api.v2.workspace.WorkspaceIdentity WorkspaceIdentity}
     * @return   parameter "description" of String
     */
    @JsonServerMethod(rpc = "Workspace.get_workspace_description", authOptional=true)
    public String getWorkspaceDescription(WorkspaceIdentity wsi, AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN get_workspace_description
		final WorkspaceIdentifier wksp = processWorkspaceIdentifier(wsi);
		returnVal = ws.getWorkspaceDescription(getUser(authPart), wksp);
        //END get_workspace_description
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: set_permissions</p>
     * <pre>
     * Set permissions for a workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.SetPermissionsParams SetPermissionsParams}
     */
    @JsonServerMethod(rpc = "Workspace.set_permissions")
    public void setPermissions(SetPermissionsParams params, AuthToken authPart) throws Exception {
        //BEGIN set_permissions
		wsmeth.setPermissions(params, getUser(authPart));
        //END set_permissions
    }

    /**
     * <p>Original spec-file function name: set_global_permission</p>
     * <pre>
     * Set the global permission for a workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.SetGlobalPermissionsParams SetGlobalPermissionsParams}
     */
    @JsonServerMethod(rpc = "Workspace.set_global_permission")
    public void setGlobalPermission(SetGlobalPermissionsParams params, AuthToken authPart) throws Exception {
        //BEGIN set_global_permission
		wsmeth.setGlobalPermission(params, getUser(authPart));
        //END set_global_permission
    }

    /**
     * <p>Original spec-file function name: set_workspace_description</p>
     * <pre>
     * Set the description for a workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.SetWorkspaceDescriptionParams SetWorkspaceDescriptionParams}
     */
    @JsonServerMethod(rpc = "Workspace.set_workspace_description")
    public void setWorkspaceDescription(SetWorkspaceDescriptionParams params, AuthToken authPart) throws Exception {
        //BEGIN set_workspace_description
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		ws.setWorkspaceDescription(getUser(authPart), wsi,
				params.getDescription());
        //END set_workspace_description
    }

    /**
     * <p>Original spec-file function name: get_permissions_mass</p>
     * <pre>
     * Get permissions for multiple workspaces.
     * </pre>
     * @param   mass   instance of type {@link us.kbase.workspace.api.v2.workspace.GetPermissionsMassParams GetPermissionsMassParams}
     * @return   parameter "perms" of type {@link us.kbase.workspace.api.v2.workspace.WorkspacePermissions WorkspacePermissions}
     */
    @JsonServerMethod(rpc = "Workspace.get_permissions_mass")
    public WorkspacePermissions getPermissionsMass(GetPermissionsMassParams mass, AuthToken authPart) throws Exception {
        WorkspacePermissions returnVal = null;
        //BEGIN get_permissions_mass
		checkAddlArgs(mass.getAdditionalProperties(), mass.getClass());
		returnVal = wsmeth.getPermissions(mass, getUser(authPart));
        //END get_permissions_mass
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_permissions</p>
     * <pre>
     * Get permissions for a workspace.
     * </pre>
     * @param   wsi   instance of type {@link us.kbase.workspace.api.v2.workspace.WorkspaceIdentity WorkspaceIdentity}
     * @return   parameter "perms" of mapping from original type "username" (Login name of a KBase user account.) to original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.)
     */
    @JsonServerMethod(rpc = "Workspace.get_permissions")
    public Map<String,String> getPermissions(WorkspaceIdentity wsi, AuthToken authPart) throws Exception {
        Map<String,String> returnVal = null;
        //BEGIN get_permissions
        returnVal = wsmeth.getPermissions(wsi, getUser(authPart));
        //END get_permissions
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: save_objects</p>
     * <pre>
     * Save objects to the workspace. Saving over a deleted object undeletes
     * it.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.SaveObjectsParams SaveObjectsParams}
     * @return   parameter "info" of list of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.save_objects")
    public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> saveObjects(SaveObjectsParams params, AuthToken authPart) throws Exception {
        List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> returnVal = null;
        //BEGIN save_objects
		returnVal = wsmeth.saveObjects(new us.kbase.workspace.SaveObjectsParams()
				.withId(params.getId())
				.withObjects(convertObjectSaveData(params.getObjects()))
				.withWorkspace(params.getWorkspace()),
				getUser(authPart), authPart);
        //END save_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_object_provenance</p>
     * <pre>
     * Get object provenance from the workspace.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "data" of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectProvenanceInfo ObjectProvenanceInfo}
     */
    @JsonServerMethod(rpc = "Workspace.get_object_provenance", authOptional=true)
    public List<ObjectProvenanceInfo> getObjectProvenance(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        List<ObjectProvenanceInfo> returnVal = null;
        //BEGIN get_object_provenance
		//this needs a better solution
		final List<ObjectIdentifier> loi =
				processObjectIdentifiers(convertObjectIds(objectIds));
		returnVal = convertProvInfo(translateObjectProvInfo(
				ws.getObjectProvenance(getUser(authPart), loi),
				getUser(authPart), handleManagerUrl, handleMgrToken, true));
        //END get_object_provenance
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_objects</p>
     * <pre>
     * Get objects from the workspace.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "data" of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectData ObjectData}
     */
    @JsonServerMethod(rpc = "Workspace.get_objects", authOptional=true)
    public List<ObjectData> getObjects(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        List<ObjectData> returnVal = null;
        //BEGIN get_objects
		final List<ObjectIdentifier> loi =
				processObjectIdentifiers(convertObjectIds(objectIds));
		final Set<ByteArrayFileCache> resources =
				new HashSet<ByteArrayFileCache>();
		returnVal = convertObjectData(translateObjectData(
				ws.getObjects(getUser(authPart), loi), getUser(authPart),
					resources, handleManagerUrl, handleMgrToken, true));
		resourcesToDelete.set(resources);
        //END get_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_object_subset</p>
     * <pre>
     * Get portions of objects from the workspace.
     * When selecting a subset of an array in an object, the returned
     * array is compressed to the size of the subset, but the ordering of
     * the array is maintained. For example, if the array stored at the
     * 'feature' key of a Genome object has 4000 entries, and the object paths
     * provided are:
     *         /feature/7
     *         /feature/3015
     *         /feature/700
     * The returned feature array will be of length three and the entries will
     * consist, in order, of the 7th, 700th, and 3015th entries of the
     * original array.
     * </pre>
     * @param   subObjectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.SubObjectIdentity SubObjectIdentity}
     * @return   parameter "data" of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectData ObjectData}
     */
    @JsonServerMethod(rpc = "Workspace.get_object_subset", authOptional=true)
    public List<ObjectData> getObjectSubset(List<SubObjectIdentity> subObjectIds, AuthToken authPart) throws Exception {
        List<ObjectData> returnVal = null;
        //BEGIN get_object_subset
		final List<SubObjectIdentifier> loi = processSubObjectIdentifiers(
				convertSubObjectIdentifers(subObjectIds));
		final Set<ByteArrayFileCache> resources =
				new HashSet<ByteArrayFileCache>();
		returnVal = convertObjectData(translateObjectData(
				ws.getObjectsSubSet(getUser(authPart), loi), getUser(authPart),
						resources, handleManagerUrl, handleMgrToken, true));
		resourcesToDelete.set(resources);
        //END get_object_subset
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_object_history</p>
     * <pre>
     * Get an object's history. The version argument of the ObjectIdentity is
     * ignored.
     * </pre>
     * @param   object   instance of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "history" of list of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.get_object_history", authOptional=true)
    public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> getObjectHistory(ObjectIdentity object, AuthToken authPart) throws Exception {
        List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> returnVal = null;
        //BEGIN get_object_history
		final ObjectIdentifier oi = processObjectIdentifier(convertObjectId(object));
		returnVal = objInfoToTuple(ws.getObjectHistory(getUser(authPart), oi),
				true);
        //END get_object_history
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_referencing_objects</p>
     * <pre>
     * List objects that reference one or more objects.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "referrers" of list of list of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.list_referencing_objects", authOptional=true)
    public List<List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>>> listReferencingObjects(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        List<List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>>> returnVal = null;
        //BEGIN list_referencing_objects
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(objectIds));
		returnVal = translateObjectDataList(
				ws.getReferencingObjects(getUser(authPart), loi), false);
        //END list_referencing_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_referencing_object_counts</p>
     * <pre>
     * List the number of times objects have been referenced.
     * This count includes both provenance and object-to-object references
     * and, unlike list_referencing_objects, includes objects that are
     * inaccessible to the user.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "counts" of list of Long
     */
    @JsonServerMethod(rpc = "Workspace.list_referencing_object_counts", authOptional=true)
    public List<Long> listReferencingObjectCounts(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        List<Long> returnVal = null;
        //BEGIN list_referencing_object_counts
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(objectIds));
		returnVal = new LinkedList<Long>();
		for (int i: ws.getReferencingObjectCounts(getUser(authPart), loi)) {
			returnVal.add((long) i);
		}
        //END list_referencing_object_counts
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_referenced_objects</p>
     * <pre>
     * Get objects by references from other objects.
     *         NOTE: In the vast majority of cases, this method is not necessary and
     *         get_objects should be used instead. 
     *         
     *         get_referenced_objects guarantees that a user that has access to an
     *         object can always see a) objects that are referenced inside the object
     *         and b) objects that are referenced in the object's provenance. This
     *         ensures that the user has visibility into the entire provenance of the
     *         object and the object's object dependencies (e.g. references).
     *         
     *         The user must have at least read access to the first object in each
     *         reference chain, but need not have access to any further objects in
     *         the chain, and those objects may be deleted.
     * </pre>
     * @param   refChains   instance of list of original type "ref_chain" (A chain of objects with references to one another. An object reference chain consists of a list of objects where the nth object possesses a reference, either in the object itself or in the object provenance, to the n+1th object.) &rarr; list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "data" of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectData ObjectData}
     */
    @JsonServerMethod(rpc = "Workspace.get_referenced_objects", authOptional=true)
    public List<ObjectData> getReferencedObjects(List<List<ObjectIdentity>> refChains, AuthToken authPart) throws Exception {
        List<ObjectData> returnVal = null;
        //BEGIN get_referenced_objects
		if (refChains == null) {
			throw new IllegalArgumentException("refChains may not be null");
		}
		final List<ObjectChain> chains = new LinkedList<ObjectChain>();
		int count = 1;
		for (List<ObjectIdentity> loy: refChains) {
			final List<ObjectIdentifier> lor;
			try {
				lor = processObjectIdentifiers(convertObjectIds(loy));
			} catch (Exception e) {
				throw new IllegalArgumentException(String.format(
						"Error on object chain #%s: %s",
						count, e.getLocalizedMessage()), e);
			}
			if (lor.size() < 2) {
				throw new IllegalArgumentException(String.format(
						"Error on object chain #%s: The minimum size of a reference chain is 2 ObjectIdentities",
						count));
			}
			chains.add(new ObjectChain(lor.get(0), lor.subList(1, lor.size())));
			count++;
		}
		final Set<ByteArrayFileCache> resources =
				new HashSet<ByteArrayFileCache>();
		returnVal = convertObjectData(translateObjectData(
				ws.getReferencedObjects(getUser(authPart), chains),
						getUser(authPart), resources, handleManagerUrl,
						handleMgrToken, true));
		resourcesToDelete.set(resources);	
        //END get_referenced_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_workspace_info</p>
     * <pre>
     * List workspaces viewable by the user.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.ListWorkspaceInfoParams ListWorkspaceInfoParams}
     * @return   parameter "wsinfo" of list of original type "workspace_info" (Information about a workspace. ws_id id - the numerical ID of the workspace. ws_name workspace - name of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. int objects - the approximate number of objects currently stored in the workspace. permission user_permission - permissions for the authenticated user of this workspace. permission globalread - whether this workspace is globally readable. lock_status lockstat - the status of the workspace lock. usermeta metadata - arbitrary user-supplied metadata about the workspace.) &rarr; tuple of size 9: parameter "id" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "owner" of original type "username" (Login name of a KBase user account.), parameter "moddate" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "object" of Long, parameter "user_permission" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "globalread" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "lockstat" of original type "lock_status" (The lock status of a workspace. One of 'unlocked', 'locked', or 'published'.), parameter "metadata" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.list_workspace_info", authOptional=true)
    public List<Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>>> listWorkspaceInfo(ListWorkspaceInfoParams params, AuthToken authPart) throws Exception {
        List<Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>>> returnVal = null;
        //BEGIN list_workspace_info
		returnVal = wsmeth.listWorkspaceInfo(params, getUser(authPart));
        //END list_workspace_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_objects</p>
     * <pre>
     * List objects in one or more workspaces.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.ListObjectsParams ListObjectsParams}
     * @return   parameter "objinfo" of list of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.list_objects", authOptional=true)
    public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> listObjects(ListObjectsParams params, AuthToken authPart) throws Exception {
        List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> returnVal = null;
        //BEGIN list_objects
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final List<WorkspaceIdentifier> wsis = new LinkedList<WorkspaceIdentifier>();
		if (params.getWorkspaces() != null) {
			for (final String ws: params.getWorkspaces()) {
				wsis.add(processWorkspaceIdentifier(ws, null));
			}
		}
		if (params.getIds() != null) {
			for (final Long id: params.getIds()) {
				wsis.add(processWorkspaceIdentifier(null, id));
			}
		}
		final TypeDefId type = params.getType() == null ? null :
				TypeDefId.fromTypeString(params.getType());
		final Permission p = params.getPerm() == null ? null :
			translatePermission(params.getPerm());
		final boolean showHidden = longToBoolean(params.getShowHidden());
		final boolean showDeleted = longToBoolean(params.getShowDeleted());
		final boolean showOnlyDeleted = longToBoolean(
				params.getShowOnlyDeleted());
		final boolean showAllVers = longToBoolean(
				params.getShowAllVersions());
		final boolean includeMetadata = longToBoolean(
				params.getIncludeMetadata());
		final boolean excludeGlobal = longToBoolean(
				params.getExcludeGlobal());
		final int skip = longToInt(params.getSkip(), "Skip", -1);
		final int limit = longToInt(params.getLimit(), "Limit", -1);
		returnVal = objInfoToTuple(
				//this sig is insane
				ws.listObjects(getUser(authPart), wsis, type, p,
						ArgUtils.convertUsers(params.getSavedby()),
						params.getMeta(), parseDate(params.getAfter()),
						parseDate(params.getBefore()), showHidden,
						showDeleted, showOnlyDeleted, showAllVers,
						includeMetadata, excludeGlobal, skip, limit),
						false);
        //END list_objects
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_object_info_new</p>
     * <pre>
     * Get information about objects from the workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.GetObjectInfoNewParams GetObjectInfoNewParams}
     * @return   parameter "info" of list of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.get_object_info_new", authOptional=true)
    public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> getObjectInfoNew(GetObjectInfoNewParams params, AuthToken authPart) throws Exception {
        List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>>> returnVal = null;
        //BEGIN get_object_info_new
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(params.getObjects()));
		returnVal = objInfoToTuple(
				ws.getObjectInformation(getUser(authPart), loi,
						longToBoolean(params.getIncludeMetadata()),
						longToBoolean(params.getIgnoreErrors())), true);
        //END get_object_info_new
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: rename_workspace</p>
     * <pre>
     * Rename a workspace.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.RenameWorkspaceParams RenameWorkspaceParams}
     * @return   parameter "renamed" of original type "workspace_info" (Information about a workspace. ws_id id - the numerical ID of the workspace. ws_name workspace - name of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. int objects - the approximate number of objects currently stored in the workspace. permission user_permission - permissions for the authenticated user of this workspace. permission globalread - whether this workspace is globally readable. lock_status lockstat - the status of the workspace lock. usermeta metadata - arbitrary user-supplied metadata about the workspace.) &rarr; tuple of size 9: parameter "id" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "owner" of original type "username" (Login name of a KBase user account.), parameter "moddate" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "object" of Long, parameter "user_permission" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "globalread" of original type "permission" (Represents the permissions a user or users have to a workspace: 'a' - administrator. All operations allowed. 'w' - read/write. 'r' - read. 'n' - no permissions.), parameter "lockstat" of original type "lock_status" (The lock status of a workspace. One of 'unlocked', 'locked', or 'published'.), parameter "metadata" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.rename_workspace")
    public Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> renameWorkspace(RenameWorkspaceParams params, AuthToken authPart) throws Exception {
        Tuple9<Long, String, String, String, Long, String, String, String, Map<String,String>> returnVal = null;
        //BEGIN rename_workspace
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi =
				processWorkspaceIdentifier(params.getWsi());
		returnVal = wsInfoToTuple(ws.renameWorkspace(getUser(authPart),
				wsi, params.getNewName()));
        //END rename_workspace
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: rename_object</p>
     * <pre>
     * Rename an object. User meta data is always returned as null.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.RenameObjectParams RenameObjectParams}
     * @return   parameter "renamed" of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.rename_object")
    public Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> renameObject(RenameObjectParams params, AuthToken authPart) throws Exception {
        Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> returnVal = null;
        //BEGIN rename_object
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final ObjectIdentifier oi = processObjectIdentifier(
				convertObjectId(params.getObj()));
		returnVal = objInfoToTuple(ws.renameObject(getUser(authPart),
				oi, params.getNewName()), true);
        //END rename_object
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: copy_object</p>
     * <pre>
     * Copy an object. Returns the object_info for the newest version.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.CopyObjectParams CopyObjectParams}
     * @return   parameter "copied" of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.copy_object")
    public Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> copyObject(CopyObjectParams params, AuthToken authPart) throws Exception {
        Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> returnVal = null;
        //BEGIN copy_object
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final ObjectIdentifier from = processObjectIdentifier(
				convertObjectId(params.getFrom()));
		final ObjectIdentifier to = processObjectIdentifier(
				convertObjectId(params.getTo()));
		returnVal = objInfoToTuple(ws.copyObject(getUser(authPart), from, to),
				true);
        //END copy_object
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: revert_object</p>
     * <pre>
     * Revert an object.
     *         The object specified in the ObjectIdentity is reverted to the version
     *         specified in the ObjectIdentity.
     * </pre>
     * @param   object   instance of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     * @return   parameter "reverted" of original type "object_info" (Information about an object, including user provided metadata. obj_id objid - the numerical id of the object. obj_name name - the name of the object. type_string type - the type of the object. timestamp save_date - the save date of the object. obj_ver ver - the version of the object. username saved_by - the user that saved or copied the object. ws_id wsid - the workspace containing the object. ws_name workspace - the workspace containing the object. string chsum - the md5 checksum of the object. int size - the size of the object in bytes. usermeta meta - arbitrary user-supplied metadata about the object.) &rarr; tuple of size 11: parameter "objid" of original type "obj_id" (The unique, permanent numerical ID of an object.), parameter "name" of original type "obj_name" (A string used as a name for an object. Any string consisting of alphanumeric characters and the characters |._- that is not an integer is acceptable.), parameter "type" of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1), parameter "save_date" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is either the character Z (representing the UTC timezone) or the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time) 2013-04-03T08:56:32Z (UTC time)), parameter "version" of Long, parameter "saved_by" of original type "username" (Login name of a KBase user account.), parameter "wsid" of original type "ws_id" (The unique, permanent numerical ID of a workspace.), parameter "workspace" of original type "ws_name" (A string used as a name for a workspace. Any string consisting of alphanumeric characters and "_", ".", or "-" that is not an integer is acceptable. The name may optionally be prefixed with the workspace owner's user name and a colon, e.g. kbasetest:my_workspace.), parameter "chsum" of String, parameter "size" of Long, parameter "meta" of original type "usermeta" (User provided metadata about an object. Arbitrary key-value pairs provided by the user.) &rarr; mapping from String to String
     */
    @JsonServerMethod(rpc = "Workspace.revert_object")
    public Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> revertObject(ObjectIdentity object, AuthToken authPart) throws Exception {
        Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> returnVal = null;
        //BEGIN revert_object
		final ObjectIdentifier oi = processObjectIdentifier(
				convertObjectId(object));
		returnVal = objInfoToTuple(ws.revertObject(getUser(authPart), oi),
				true);
        //END revert_object
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: hide_objects</p>
     * <pre>
     * Hide objects. All versions of an object are hidden, regardless of
     * the version specified in the ObjectIdentity. Hidden objects do not
     * appear in the list_objects method.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     */
    @JsonServerMethod(rpc = "Workspace.hide_objects")
    public void hideObjects(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        //BEGIN hide_objects
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(objectIds));
		ws.setObjectsHidden(getUser(authPart), loi, true);
        //END hide_objects
    }

    /**
     * <p>Original spec-file function name: unhide_objects</p>
     * <pre>
     * Unhide objects. All versions of an object are unhidden, regardless
     * of the version specified in the ObjectIdentity.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     */
    @JsonServerMethod(rpc = "Workspace.unhide_objects")
    public void unhideObjects(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        //BEGIN unhide_objects
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(objectIds));
		ws.setObjectsHidden(getUser(authPart), loi, false);
        //END unhide_objects
    }

    /**
     * <p>Original spec-file function name: delete_objects</p>
     * <pre>
     * Delete objects. All versions of an object are deleted, regardless of
     * the version specified in the ObjectIdentity.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     */
    @JsonServerMethod(rpc = "Workspace.delete_objects")
    public void deleteObjects(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        //BEGIN delete_objects
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(objectIds));
		ws.setObjectsDeleted(getUser(authPart), loi, true);
        //END delete_objects
    }

    /**
     * <p>Original spec-file function name: undelete_objects</p>
     * <pre>
     * Undelete objects. All versions of an object are undeleted, regardless
     * of the version specified in the ObjectIdentity. If an object is not
     * deleted, no error is thrown.
     * </pre>
     * @param   objectIds   instance of list of type {@link us.kbase.workspace.api.v2.workspace.ObjectIdentity ObjectIdentity}
     */
    @JsonServerMethod(rpc = "Workspace.undelete_objects")
    public void undeleteObjects(List<ObjectIdentity> objectIds, AuthToken authPart) throws Exception {
        //BEGIN undelete_objects
		final List<ObjectIdentifier> loi = processObjectIdentifiers(
				convertObjectIds(objectIds));
		ws.setObjectsDeleted(getUser(authPart), loi, false);
        //END undelete_objects
    }

    /**
     * <p>Original spec-file function name: delete_workspace</p>
     * <pre>
     * Delete a workspace. All objects contained in the workspace are deleted.
     * </pre>
     * @param   wsi   instance of type {@link us.kbase.workspace.api.v2.workspace.WorkspaceIdentity WorkspaceIdentity}
     */
    @JsonServerMethod(rpc = "Workspace.delete_workspace")
    public void deleteWorkspace(WorkspaceIdentity wsi, AuthToken authPart) throws Exception {
        //BEGIN delete_workspace
		final WorkspaceIdentifier wksp = processWorkspaceIdentifier(wsi);
		ws.setWorkspaceDeleted(getUser(authPart), wksp, true);
        //END delete_workspace
    }

    /**
     * <p>Original spec-file function name: undelete_workspace</p>
     * <pre>
     * Undelete a workspace. All objects contained in the workspace are
     * undeleted, regardless of their state at the time the workspace was
     * deleted.
     * </pre>
     * @param   wsi   instance of type {@link us.kbase.workspace.api.v2.workspace.WorkspaceIdentity WorkspaceIdentity}
     */
    @JsonServerMethod(rpc = "Workspace.undelete_workspace")
    public void undeleteWorkspace(WorkspaceIdentity wsi, AuthToken authPart) throws Exception {
        //BEGIN undelete_workspace
    	final WorkspaceIdentifier wksp = processWorkspaceIdentifier(wsi);
		ws.setWorkspaceDeleted(getUser(authPart), wksp, false);
        //END undelete_workspace
    }

    /**
     * <p>Original spec-file function name: request_module_ownership</p>
     * <pre>
     * Request ownership of a module name. A Workspace administrator
     * must approve the request.
     * </pre>
     * @param   mod   instance of original type "modulename" (A module name defined in a KIDL typespec.)
     */
    @JsonServerMethod(rpc = "Workspace.request_module_ownership")
    public void requestModuleOwnership(String mod, AuthToken authPart) throws Exception {
        //BEGIN request_module_ownership
		final WorkspaceUser u = getUser(authPart);
		ws.requestModuleRegistration(u, mod);
		//bail on this, there's no mail daemon running on magellean AFAIK
//		wsadmin.notifyOnModuleRegRequest(authPart, u, mod);
        //END request_module_ownership
    }

    /**
     * <p>Original spec-file function name: register_typespec</p>
     * <pre>
     * Register a new typespec or recompile a previously registered typespec
     * with new options.
     * See the documentation of RegisterTypespecParams for more details.
     * Also see the release_types function.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.RegisterTypespecParams RegisterTypespecParams}
     * @return   instance of mapping from original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1) to original type "jsonschema" (The JSON Schema (v4) representation of a type definition.)
     */
    @JsonServerMethod(rpc = "Workspace.register_typespec")
    public Map<String,String> registerTypespec(RegisterTypespecParams params, AuthToken authPart) throws Exception {
        Map<String,String> returnVal = null;
        //BEGIN register_typespec
		//TODO improve parse errors, don't need include path, currentlyCompiled
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		if (!(params.getMod() == null) ^ (params.getSpec() == null)) {
			throw new IllegalArgumentException(
					"Must provide either a spec or module name");
		}
		final List<String> add = params.getNewTypes() != null ?
				params.getNewTypes() : new ArrayList<String>();
		final List<String> rem = params.getRemoveTypes() != null ?
				params.getRemoveTypes() : new ArrayList<String>();
		final Map<String, Long> deps = params.getDependencies() != null ?
				params.getDependencies() : new HashMap<String, Long>();
		final Map<TypeDefName, TypeChange> res;
		if (params.getMod() != null) {
			 res = ws.compileTypeSpec(getUser(authPart), params.getMod(),
					add, rem, deps, params.getDryrun() == null ? true :
						params.getDryrun() != 0);
		} else {
			res = ws.compileNewTypeSpec(getUser(authPart), params.getSpec(),
					add, rem, deps, params.getDryrun() == null ? true :
						params.getDryrun() != 0, params.getPrevVer());
		}
		returnVal = new HashMap<String, String>();
		for (final TypeChange tc: res.values()) {
			if (!tc.isUnregistered()) {
				returnVal.put(tc.getTypeVersion().getTypeString(),
						tc.getJsonSchema());
			}
		}
        //END register_typespec
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: register_typespec_copy</p>
     * <pre>
     * Register a copy of new typespec or refresh an existing typespec which is
     * loaded from another workspace for synchronization. Method returns new
     * version of module in current workspace.
     * Also see the release_types function.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.RegisterTypespecCopyParams RegisterTypespecCopyParams}
     * @return   parameter "new_local_version" of original type "spec_version" (The version of a typespec file.)
     */
    @JsonServerMethod(rpc = "Workspace.register_typespec_copy")
    public Long registerTypespecCopy(RegisterTypespecCopyParams params, AuthToken authPart) throws Exception {
        Long returnVal = null;
        //BEGIN register_typespec_copy
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		if (params.getExternalWorkspaceUrl() == null) {
			throw new IllegalArgumentException(
					"Must provide a URL for an external workspace service");
		}
		if (params.getMod() == null) {
			throw new IllegalArgumentException(
					"Must provide a module name");
		}
		final WorkspaceClient client = new WorkspaceClient(
				new URL(params.getExternalWorkspaceUrl()), authPart);
		if (!params.getExternalWorkspaceUrl().startsWith("https:"))
			client.setIsInsecureHttpConnectionAllowed(true);
		final GetModuleInfoParams gmiparams = new GetModuleInfoParams()
			.withMod(params.getMod()).withVer(params.getVersion());
		final ModuleInfo extInfo =
				client.getModuleInfo(gmiparams);
		final Map<String, String> includesToMd5 = new HashMap<String, String>();
		for (final Map.Entry<String, Long> entry : extInfo
				.getIncludedSpecVersion().entrySet()) {
			final String includedModule = entry.getKey();
			final long extIncludedVer = entry.getValue();
			final GetModuleInfoParams includeParams = new GetModuleInfoParams()
				.withMod(includedModule).withVer(extIncludedVer);
			final ModuleInfo extIncludedInfo = 
					client.getModuleInfo(includeParams);
			includesToMd5.put(includedModule, extIncludedInfo.getChsum());
		}
		final String userId = authPart.getUserName();
		final String specDocument = extInfo.getSpec();
		final Set<String> extTypeSet = new LinkedHashSet<String>();
		for (final String typeDef : extInfo.getTypes().keySet())
			extTypeSet.add(TypeDefId.fromTypeString(typeDef).getType().getName());
		returnVal = ws.compileTypeSpecCopy(params.getMod(), specDocument,
				extTypeSet, userId, includesToMd5, 
				extInfo.getIncludedSpecVersion());
        //END register_typespec_copy
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: release_module</p>
     * <pre>
     * Release a module for general use of its types.
     * Releases the most recent version of a module. Releasing a module does
     * two things to the module's types:
     * 1) If a type's major version is 0, it is changed to 1. A major
     *         version of 0 implies that the type is in development and may have
     *         backwards incompatible changes from minor version to minor version.
     *         Once a type is released, backwards incompatible changes always
     *         cause a major version increment.
     * 2) This version of the type becomes the default version, and if a 
     *         specific version is not supplied in a function call, this version
     *         will be used. This means that newer, unreleased versions of the
     *         type may be skipped.
     * </pre>
     * @param   mod   instance of original type "modulename" (A module name defined in a KIDL typespec.)
     * @return   parameter "types" of list of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     */
    @JsonServerMethod(rpc = "Workspace.release_module")
    public List<String> releaseModule(String mod, AuthToken authPart) throws Exception {
        List<String> returnVal = null;
        //BEGIN release_module
		returnVal = new LinkedList<String>();
		final List<AbsoluteTypeDefId> ret = ws.releaseTypes(getUser(authPart),
				mod);
		for (final AbsoluteTypeDefId t: ret) {
			returnVal.add(t.getTypeString());
		}
        //END release_module
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_modules</p>
     * <pre>
     * List typespec modules.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.ListModulesParams ListModulesParams}
     * @return   parameter "modules" of list of original type "modulename" (A module name defined in a KIDL typespec.)
     */
    @JsonServerMethod(rpc = "Workspace.list_modules")
    public List<String> listModules(ListModulesParams params) throws Exception {
        List<String> returnVal = null;
        //BEGIN list_modules
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		WorkspaceUser user = null;
		if (params.getOwner() != null) {
			user = new WorkspaceUser(params.getOwner());
		}
		returnVal = ws.listModules(user);
        //END list_modules
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_module_versions</p>
     * <pre>
     * List typespec module versions.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.ListModuleVersionsParams ListModuleVersionsParams}
     * @return   parameter "vers" of type {@link us.kbase.workspace.api.v2.workspace.ModuleVersions ModuleVersions}
     */
    @JsonServerMethod(rpc = "Workspace.list_module_versions", authOptional=true)
    public ModuleVersions listModuleVersions(ListModuleVersionsParams params, AuthToken authPart) throws Exception {
        ModuleVersions returnVal = null;
        //BEGIN list_module_versions
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		if (!(params.getMod() == null ^ params.getType() == null)) {
			throw new IllegalArgumentException(
					"Must provide either a module name or a type");
		}
		final List<Long> vers;
		final String module;
		if (params.getMod() != null) {
			vers = ws.getModuleVersions(params.getMod(), getUser(authPart));
			module = params.getMod();
		} else {
			final TypeDefId type = TypeDefId.fromTypeString(params.getType());
			vers = ws.getModuleVersions(type, getUser(authPart));
			module = type.getType().getModule();
		}
		returnVal = new ModuleVersions().withMod(module).withVers(vers);
        //END list_module_versions
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_module_info</p>
     * <pre>
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.GetModuleInfoParams GetModuleInfoParams}
     * @return   parameter "info" of type {@link us.kbase.workspace.api.v2.workspace.ModuleInfo ModuleInfo}
     */
    @JsonServerMethod(rpc = "Workspace.get_module_info", authOptional=true)
    public ModuleInfo getModuleInfo(GetModuleInfoParams params, AuthToken authPart) throws Exception {
        ModuleInfo returnVal = null;
        //BEGIN get_module_info
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		if (params.getMod() == null) {
			throw new IllegalArgumentException(
					"Must provide a module name");
		}
		final ModuleDefId module;
		if (params.getVer() != null) {
			module = new ModuleDefId(params.getMod(), params.getVer());
		} else {
			module = new ModuleDefId(params.getMod());
		}
		WorkspaceUser user = getUser(authPart);
		final us.kbase.workspace.database.ModuleInfo mi =
				ws.getModuleInfo(user, module);
		final Map<String, String> types = new HashMap<String, String>();
		for (final AbsoluteTypeDefId t: mi.getTypes().keySet()) {
			types.put(t.getTypeString(), mi.getTypes().get(t));
		}
		returnVal = new ModuleInfo()
				.withDescription(mi.getDescription())
				.withOwners(mi.getOwners())
				.withSpec(mi.getTypespec())
				.withVer(mi.getVersion())
				.withTypes(types)
				.withIncludedSpecVersion(mi.getIncludedSpecVersions())
				.withChsum(mi.getMd5hash())
				.withFunctions(mi.getFunctions())
				.withIsReleased(mi.isReleased() ? 1L : 0L);
        //END get_module_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_jsonschema</p>
     * <pre>
     * Get JSON schema for a type.
     * </pre>
     * @param   type   instance of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     * @return   parameter "schema" of original type "jsonschema" (The JSON Schema (v4) representation of a type definition.)
     */
    @JsonServerMethod(rpc = "Workspace.get_jsonschema", authOptional=true)
    public String getJsonschema(String type, AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN get_jsonschema
		returnVal = ws.getJsonSchema(TypeDefId.fromTypeString(type), getUser(authPart));
        //END get_jsonschema
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: translate_from_MD5_types</p>
     * <pre>
     * Translation from types qualified with MD5 to their semantic versions
     * </pre>
     * @param   md5Types   instance of list of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     * @return   parameter "sem_types" of mapping from original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1) to list of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     */
    @JsonServerMethod(rpc = "Workspace.translate_from_MD5_types")
    public Map<String,List<String>> translateFromMD5Types(List<String> md5Types) throws Exception {
        Map<String,List<String>> returnVal = null;
        //BEGIN translate_from_MD5_types
        returnVal = ws.translateFromMd5Types(md5Types);
        //END translate_from_MD5_types
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: translate_to_MD5_types</p>
     * <pre>
     * Translation from types qualified with semantic versions to their MD5'ed versions
     * </pre>
     * @param   semTypes   instance of list of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     * @return   parameter "md5_types" of mapping from original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1) to original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     */
    @JsonServerMethod(rpc = "Workspace.translate_to_MD5_types", authOptional=true)
    public Map<String,String> translateToMD5Types(List<String> semTypes, AuthToken authPart) throws Exception {
        Map<String,String> returnVal = null;
        //BEGIN translate_to_MD5_types
        returnVal = ws.translateToMd5Types(semTypes, getUser(authPart));
        //END translate_to_MD5_types
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_type_info</p>
     * <pre>
     * </pre>
     * @param   type   instance of original type "type_string" (A type string. Specifies the type and its version in a single string in the format [module].[typename]-[major].[minor]: module - a string. The module name of the typespec containing the type. typename - a string. The name of the type as assigned by the typedef statement. major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyType-3.1)
     * @return   parameter "info" of type {@link us.kbase.workspace.api.v2.workspace.TypeInfo TypeInfo}
     */
    @JsonServerMethod(rpc = "Workspace.get_type_info", authOptional=true)
    public TypeInfo getTypeInfo(String type, AuthToken authPart) throws Exception {
        TypeInfo returnVal = null;
        //BEGIN get_type_info
        TypeDetailedInfo tdi = ws.getTypeInfo(type, true, getUser(authPart));
        returnVal = new TypeInfo().withTypeDef(tdi.getTypeDefId())
        		.withDescription(tdi.getDescription())
        		.withSpecDef(tdi.getSpecDef())
        		.withJsonSchema(tdi.getJsonSchema())
        		.withParsingStructure(tdi.getParsingStructure())
        		.withModuleVers(tdi.getModuleVersions())
        		.withReleasedModuleVers(tdi.getReleasedModuleVersions())
        		.withTypeVers(tdi.getTypeVersions())
        		.withReleasedTypeVers(tdi.getReleasedTypeVersions())
        		.withUsingFuncDefs(tdi.getUsingFuncDefIds())
        		.withUsingTypeDefs(tdi.getUsingTypeDefIds())
        		.withUsedTypeDefs(tdi.getUsedTypeDefIds());
        //END get_type_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_all_type_info</p>
     * <pre>
     * </pre>
     * @param   mod   instance of original type "modulename" (A module name defined in a KIDL typespec.)
     * @return   instance of list of type {@link us.kbase.workspace.api.v2.workspace.TypeInfo TypeInfo}
     */
    @JsonServerMethod(rpc = "Workspace.get_all_type_info", authOptional=true)
    public List<TypeInfo> getAllTypeInfo(String mod, AuthToken authPart) throws Exception {
        List<TypeInfo> returnVal = null;
        //BEGIN get_all_type_info
        returnVal = new ArrayList<TypeInfo>();
        ModuleInfo mi = getModuleInfo(new GetModuleInfoParams().withMod(mod), authPart);
        for (String typeDef : mi.getTypes().keySet())
        	returnVal.add(getTypeInfo(typeDef, authPart));
        //END get_all_type_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_func_info</p>
     * <pre>
     * </pre>
     * @param   func   instance of original type "func_string" (A function string for referencing a funcdef. Specifies the function and its version in a single string in the format [modulename].[funcname]-[major].[minor]: modulename - a string. The name of the module containing the function. funcname - a string. The name of the function as assigned by the funcdef statement. major - an integer. The major version of the function. A change in the major version implies the function has changed in a non-backwards compatible way. minor - an integer. The minor version of the function. A change in the minor version implies that the function has changed in a way that is backwards compatible with previous function definitions. In many cases, the major and minor versions are optional, and if not provided the most recent version will be used. Example: MyModule.MyFunc-3.1)
     * @return   parameter "info" of type {@link us.kbase.workspace.api.v2.workspace.FuncInfo FuncInfo}
     */
    @JsonServerMethod(rpc = "Workspace.get_func_info", authOptional=true)
    public FuncInfo getFuncInfo(String func, AuthToken authPart) throws Exception {
        FuncInfo returnVal = null;
        //BEGIN get_func_info
        FuncDetailedInfo fdi = ws.getFuncInfo(func, true, getUser(authPart));
        returnVal = new FuncInfo().withFuncDef(fdi.getFuncDefId())
        		.withDescription(fdi.getDescription())
        		.withSpecDef(fdi.getSpecDef())
        		.withParsingStructure(fdi.getParsingStructure())
        		.withModuleVers(fdi.getModuleVersions())
        		.withReleasedModuleVers(fdi.getReleasedModuleVersions())
        		.withFuncVers(fdi.getFuncVersions())
        		.withReleasedFuncVers(fdi.getReleasedFuncVersions())
        		.withUsedTypeDefs(fdi.getUsedTypeDefIds());
        //END get_func_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_all_func_info</p>
     * <pre>
     * </pre>
     * @param   mod   instance of original type "modulename" (A module name defined in a KIDL typespec.)
     * @return   parameter "info" of list of type {@link us.kbase.workspace.api.v2.workspace.FuncInfo FuncInfo}
     */
    @JsonServerMethod(rpc = "Workspace.get_all_func_info", authOptional=true)
    public List<FuncInfo> getAllFuncInfo(String mod, AuthToken authPart) throws Exception {
        List<FuncInfo> returnVal = null;
        //BEGIN get_all_func_info
        returnVal = new ArrayList<FuncInfo>();
        ModuleInfo mi = getModuleInfo(new GetModuleInfoParams().withMod(mod), authPart);
        for (String funcDef : mi.getFunctions())
        	returnVal.add(getFuncInfo(funcDef, authPart));
        //END get_all_func_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: grant_module_ownership</p>
     * <pre>
     * Grant ownership of a module. You must have grant ability on the
     * module.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.GrantModuleOwnershipParams GrantModuleOwnershipParams}
     */
    @JsonServerMethod(rpc = "Workspace.grant_module_ownership")
    public void grantModuleOwnership(GrantModuleOwnershipParams params, AuthToken authPart) throws Exception {
        //BEGIN grant_module_ownership
		wsmeth.grantModuleOwnership(params, getUser(authPart), false);
        //END grant_module_ownership
    }

    /**
     * <p>Original spec-file function name: remove_module_ownership</p>
     * <pre>
     * Remove ownership from a current owner. You must have the grant ability
     * on the module.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.RemoveModuleOwnershipParams RemoveModuleOwnershipParams}
     */
    @JsonServerMethod(rpc = "Workspace.remove_module_ownership")
    public void removeModuleOwnership(RemoveModuleOwnershipParams params, AuthToken authPart) throws Exception {
        //BEGIN remove_module_ownership
		wsmeth.removeModuleOwnership(params, getUser(authPart), false);
        //END remove_module_ownership
    }

    /**
     * <p>Original spec-file function name: list_all_types</p>
     * <pre>
     * List all released types with released version from all modules. Return
     * mapping from module name to mapping from type name to released type
     * version.
     * </pre>
     * @param   params   instance of type {@link us.kbase.workspace.api.v2.workspace.ListAllTypesParams ListAllTypesParams}
     * @return   instance of mapping from original type "modulename" (A module name defined in a KIDL typespec.) to mapping from original type "typename" (A type definition name in a KIDL typespec.) to original type "typever" (A version of a type. Specifies the version of the type  in a single string in the format [major].[minor]: major - an integer. The major version of the type. A change in the major version implies the type has changed in a non-backwards compatible way. minor - an integer. The minor version of the type. A change in the minor version implies that the type has changed in a way that is backwards compatible with previous type definitions.)
     */
    @JsonServerMethod(rpc = "Workspace.list_all_types", authOptional=true)
    public Map<String,Map<String,String>> listAllTypes(ListAllTypesParams params, AuthToken authPart) throws Exception {
        Map<String,Map<String,String>> returnVal = null;
        //BEGIN list_all_types
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		returnVal = ws.listAllTypes(params.getWithEmptyModules() != null && params.getWithEmptyModules() != 0L);
        //END list_all_types
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: administer</p>
     * <pre>
     * The administration interface.
     * </pre>
     * @param   command   instance of unspecified object
     * @return   parameter "response" of unspecified object
     */
    @JsonServerMethod(rpc = "Workspace.administer")
    public UObject administer(UObject command, AuthToken authPart) throws Exception {
        UObject returnVal = null;
        //BEGIN administer
		returnVal = new UObject(wsadmin.runCommand(authPart, command));
        //END administer
        return returnVal;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: <program> <server_port>");
            return;
        }
        new WorkspaceServer().startupServer(Integer.parseInt(args[0]));
    }
}