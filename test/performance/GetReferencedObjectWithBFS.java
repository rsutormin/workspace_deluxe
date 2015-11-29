package performance;

import java.io.File;
import java.nio.file.Paths;

import us.kbase.common.mongo.GetMongoDB;
import us.kbase.common.test.controllers.mongo.MongoController;
import us.kbase.typedobj.core.TempFilesManager;
import us.kbase.typedobj.core.TypedObjectValidator;
import us.kbase.typedobj.db.MongoTypeStorage;
import us.kbase.typedobj.db.TypeDefinitionDB;
import us.kbase.workspace.database.DefaultReferenceParser;
import us.kbase.workspace.database.ResourceUsageConfigurationBuilder;
import us.kbase.workspace.database.Workspace;
import us.kbase.workspace.database.mongo.GridFSBlobStore;
import us.kbase.workspace.database.mongo.MongoWorkspaceDB;
import us.kbase.workspace.test.WorkspaceTestCommon;

import com.mongodb.DB;

/** Code for testing the performance cost of performing a BFS up the reference
 * graph in order to find a path from a user-accessible object to the user
 * requested object.
 * @author gaprice@lbl.gov
 *
 */
public class GetReferencedObjectWithBFS {

	public static void main(String[] args) throws Exception {
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
		Workspace work = new Workspace(mwdb,
				new ResourceUsageConfigurationBuilder().build(),
				new DefaultReferenceParser());
		
		runLinearReferencesTest(work);
		

		
		tfm.cleanup();
		mongo.destroy(true);
	}

	private static void runLinearReferencesTest(Workspace work) {
		// TODO Auto-generated method stub
		
	}

}
