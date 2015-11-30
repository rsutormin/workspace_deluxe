package performance;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;

import us.kbase.common.mongo.GetMongoDB;
import us.kbase.common.test.controllers.mongo.MongoController;
import us.kbase.typedobj.core.AbsoluteTypeDefId;
import us.kbase.typedobj.core.TempFilesManager;
import us.kbase.typedobj.core.TypeDefName;
import us.kbase.typedobj.core.TypedObjectValidator;
import us.kbase.typedobj.db.MongoTypeStorage;
import us.kbase.typedobj.db.TypeDefinitionDB;
import us.kbase.typedobj.idref.IdReferenceHandlerSetFactory;
import us.kbase.workspace.database.DefaultReferenceParser;
import us.kbase.workspace.database.ObjectIdentifier;
import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.Provenance;
import us.kbase.workspace.database.ResourceUsageConfigurationBuilder;
import us.kbase.workspace.database.Workspace;
import us.kbase.workspace.database.WorkspaceIdentifier;
import us.kbase.workspace.database.WorkspaceSaveObject;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.database.mongo.GridFSBlobStore;
import us.kbase.workspace.database.mongo.MongoWorkspaceDB;
import us.kbase.workspace.test.WorkspaceTestCommon;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.mongodb.DB;

/** Code for testing the performance cost of performing a BFS up the reference
 * graph in order to find a path from a user-accessible object to the user
 * requested object.
 * @author gaprice@lbl.gov
 *
 */
public class GetReferencedObjectWithBFS {
	
	private static final int LINEAR_TEST_REPS = 3;
	
	private static final String MOD_NAME_STR = "TestModule";
	private static final String LEAF_TYPE_STR = "LeafType";
	private static final String REF_TYPE_STR = "RefType";
	
	private static final AbsoluteTypeDefId LEAF_TYPE =
			new AbsoluteTypeDefId(
					new TypeDefName(MOD_NAME_STR, LEAF_TYPE_STR), 1, 0);
	private static final AbsoluteTypeDefId REF_TYPE =
			new AbsoluteTypeDefId(
					new TypeDefName(MOD_NAME_STR, REF_TYPE_STR), 1, 0);
	
	private static Workspace WS;

	public static void main(String[] args) throws Exception {
		final Logger rootLogger = ((Logger) LoggerFactory.getLogger(
				org.slf4j.Logger.ROOT_LOGGER_NAME));
		rootLogger.setLevel(Level.OFF);
		
		MongoController mongo = new MongoController(
				WorkspaceTestCommon.getMongoExe(),
				Paths.get(WorkspaceTestCommon.getTempDir()),
				WorkspaceTestCommon.useWiredTigerEngine());
		System.out.println("Using Mongo temp dir " + mongo.getTempDir());
		DB wsdb = GetMongoDB.getDB("localhost:" + mongo.getServerPort(),
				"GetReferencedObjectBFSTest");
		WorkspaceTestCommon.destroyWSandTypeDBs(wsdb,
				"GetReferencedObjectBFSTest_types");
		
		TempFilesManager tfm = new TempFilesManager(
				new File(WorkspaceTestCommon.getTempDir()));
		tfm.cleanup();
		
		TypedObjectValidator val = new TypedObjectValidator(
				new TypeDefinitionDB(new MongoTypeStorage(
						GetMongoDB.getDB("localhost:" + mongo.getServerPort(),
								"GetReferencedObjectBFSTest_types"))));
		MongoWorkspaceDB mwdb = new MongoWorkspaceDB(wsdb,
				new GridFSBlobStore(wsdb), tfm, val);
		WS = new Workspace(mwdb,
				new ResourceUsageConfigurationBuilder().build(),
				new DefaultReferenceParser());
		
		installTypes();
		runLinearReferencesTest();
		

		
		tfm.cleanup();
		mongo.destroy(true);
	}

	private static void installTypes() throws Exception {
		WorkspaceUser foo = new WorkspaceUser("foo");
		//simple spec
		WS.requestModuleRegistration(foo, MOD_NAME_STR);
		WS.resolveModuleRegistration(MOD_NAME_STR, true);
		WS.compileNewTypeSpec(foo, 
				"module " + MOD_NAME_STR + " {" +
					"/* @optional thing */" +
					"typedef structure {" +
						"string thing;" +
					"} " + LEAF_TYPE_STR + ";" +
					"/* @id ws */" +
					"typedef string reference;" +
					"typedef structure {" +
						"list<reference> refs;" +
					"} " + REF_TYPE_STR + ";" +
				"};",
				Arrays.asList(LEAF_TYPE_STR, REF_TYPE_STR), null, null, false, null);
		WS.releaseTypes(foo, MOD_NAME_STR);
	}

	private static void runLinearReferencesTest() throws Exception {
		WorkspaceUser u1 = new WorkspaceUser("u1");
		WorkspaceUser u2 = new WorkspaceUser("u2");
		WS.createWorkspace(u1, "priv", false, null, null);
		WorkspaceIdentifier priv = new WorkspaceIdentifier("priv");
		WS.createWorkspace(u1, "read", true, null, null);
		WorkspaceIdentifier read = new WorkspaceIdentifier("read");
		
		IdReferenceHandlerSetFactory fac = new IdReferenceHandlerSetFactory(10000);
		Provenance p = new Provenance(u1);
		ObjectInformation o = WS.saveObjects(u1, priv, Arrays.asList(
				new WorkspaceSaveObject(new HashMap<String, String>(), LEAF_TYPE,
						null, p, false)), fac).get(0);
		
		for (int i = 2; i <= 50; i++) {
			o = saveRefData(u1, priv, o);
		}
		o = saveRefData(u1, read, o);
		
		for (int i = 50; i > 0; i--) {
			System.out.print(i + " ");
			for (int j = 0; j < LINEAR_TEST_REPS; j++) {
				long start = System.nanoTime();
				WS.getObjects(u2, Arrays.asList(new ObjectIdentifier(priv, i)), true);
				System.out.print((System.nanoTime() - start) + " ");
			}
			System.out.println();
		}
	}

	private static ObjectInformation saveRefData(
			WorkspaceUser u1,
			WorkspaceIdentifier priv,
			ObjectInformation o)
			throws Exception {
		String ref = o.getWorkspaceId() + "/" + o.getObjectId() + "/" + o.getVersion();
		Map<String, List<String>> refdata = new HashMap<String, List<String>>();
		refdata.put("refs", Arrays.asList(ref));
		IdReferenceHandlerSetFactory fac = new IdReferenceHandlerSetFactory(10000);
		Provenance p = new Provenance(u1);
		return WS.saveObjects(u1, priv, Arrays.asList(
				new WorkspaceSaveObject(refdata, REF_TYPE, null, p, false)), fac).get(0);
	}

}
