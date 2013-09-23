package us.kbase.typedobj.db;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.cfg.ValidationConfiguration;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;

import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbList;
import us.kbase.kidl.KbMapping;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbParameter;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KidlParser;
import us.kbase.typedobj.core.validatorconfig.ValidationConfigurationFactory;
import us.kbase.typedobj.exceptions.*;

/**
 * This abstract base class provides methods/interface for retrieving a JSON Schema document and
 * JSON Schema validators for typed objects.
 * 
 * @author msneddon
 *
 */
public class TypeDefinitionDB {

	
	/**
	 * This is the factory used to create a JsonSchema object from a Json Schema
	 * document stored in the DB.
	 */
	protected JsonSchemaFactory jsonSchemaFactory; 
	
	/**
	 * The Jackson ObjectMapper which can translate a raw Json Schema document to
	 * a JsonTree that can be handled by the validator.
	 */
	protected ObjectMapper mapper;
	
	protected static SemanticVersion defaultVersion = new SemanticVersion(0, 1);
	protected static SemanticVersion releaseVersion = new SemanticVersion(1, 0);
	
	protected final TypeStorage storage;
	private final File parentTempDir;
	private final boolean withApprovalQueue;
	private final UserInfoProvider uip;
	
	
	/**
	 * Set up a new DB pointing to the specified db folder.  The contents
	 * should have a separate folder for each module named with the module
	 * name, and in each folder a set of .json files with json schema
	 * documents named the same as the type names.
	 * @param storage low level storage object
	 */
	public TypeDefinitionDB(TypeStorage storage, UserInfoProvider uip) {
		this(storage, null, uip);
	}
	
	public TypeDefinitionDB(TypeStorage storage, File tempDir, UserInfoProvider uip) {
		this(storage, tempDir, false, uip);
	}
	
	public TypeDefinitionDB(TypeStorage storage, File tempDir, boolean withApprovalQueue, UserInfoProvider uip) {
		this.mapper = new ObjectMapper();
		// Create the custom json schema factory for KBase typed objects and use this
		ValidationConfiguration kbcfg = ValidationConfigurationFactory.buildKBaseWorkspaceConfiguration();
		this.jsonSchemaFactory = JsonSchemaFactory.newBuilder()
									.setValidationConfiguration(kbcfg)
									.freeze();
		this.storage = storage;
		if (tempDir == null) {
			this.parentTempDir = new File(".");
		} else {
			this.parentTempDir = tempDir;
		}
		this.withApprovalQueue = withApprovalQueue;
		this.uip = uip;
	}
		
	/**
	 * Given a module and a type name, return true if the type exists, false otherwise
	 * @param moduleName
	 * @param typeName
	 * @return true if valid, false otherwise
	 * @throws TypeStorageException 
	 */
	public boolean isValidType(String moduleName, String typeName) throws TypeStorageException {
		return isValidType(moduleName, typeName, null);
	}
		
	/**
	 * Given a moduleName and typeName, return the JSON Schema document for the type. No version
	 * number is specified, so the latest version on record is always the schema returned if the
	 * underlying Json Schema database supports versioned typed objects.
	 * @param moduleName
	 * @param typeName
	 * @return JSON Schema document as a String
	 * @throws NoSuchTypeException
	 */
	public String getJsonSchemaDocument(String moduleName, String typeName) throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		return getJsonSchemaDocument(moduleName, typeName, null);
	}

	/**
	 * The default implementation for getting a JsonSchema object that can be used as a validator.  This
	 * method creates a new JsonSchema object on each call.  If we implement caching of the validator
	 * objects for better performance, this is the method we would need to extend.
	 * @param moduleName
	 * @param typeName
	 * @return
	 * @throws NoSuchTypeException
	 */
	public JsonSchema getJsonSchema(String moduleName, String typeName)
			throws NoSuchTypeException, NoSuchModuleException, BadJsonSchemaDocumentException, TypeStorageException
	{
		// first we retrieve the Json Schema document, this can throw a NoSuchTypeException
		String jsonSchemaDocument = getJsonSchemaDocument(moduleName, typeName);
		
		// next we parse the document into a JsonSchema using our jsonSchemaFactory
		// if there are problems, we catch and throw up an error indicating a bad document
		return jsonSchemaFromString(moduleName, typeName, jsonSchemaDocument);
	}

	protected JsonSchema jsonSchemaFromString(String moduleName,
			String typeName, String jsonSchemaDocument)
			throws BadJsonSchemaDocumentException, TypeStorageException {
		try {
			JsonNode schemaRootNode = mapper.readTree(jsonSchemaDocument);
			return jsonSchemaFactory.getJsonSchema(schemaRootNode);
		} catch (Exception e) {
			throw new BadJsonSchemaDocumentException("schema for typed object '"+moduleName+"."+typeName+"' was not a valid or readable JSON document",e);
		}
	}
	
	/**
	 * Given a moduleName and typeName, return the SPEC parsing document for the type. No version
	 * number is specified, so the latest version of document will be returned.
	 * @param moduleName
	 * @param typeName
	 * @return JSON Schema document as a String
	 * @throws NoSuchTypeException
	 */
	public KbTypedef getTypeParsingDocument(String moduleName, String typeName) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		return getTypeParsingDocument(moduleName, typeName, null);
	}

	public boolean isValidModule(String moduleName) throws TypeStorageException {
		return storage.checkModuleInfoRecordExist(moduleName) && storage.checkModuleSpecRecordExist(moduleName);
	}

	protected void checkModule(String moduleName) throws NoSuchModuleException, TypeStorageException {
		if (!isValidModule(moduleName))
			throw new NoSuchModuleException("Module wasn't uploaded: " + moduleName);
	}

	protected void checkModuleRegistered(String moduleName) throws NoSuchModuleException, TypeStorageException {
		if (!storage.checkModuleInfoRecordExist(moduleName))
			throw new NoSuchModuleException("Module wasn't registered: " + moduleName);
	}

	/**
	 * Given a module, a typeName and version, return true if the type exists, false otherwise.
	 * If version parameter is null (no version number is specified) then the latest version is 
	 * used for checking schema.
	 * @param moduleName
	 * @param typeName
	 * @param version
	 * @return true if valid, false otherwise
	 */
	public boolean isValidType(String moduleName, String typeName, String version) throws TypeStorageException {
		if (!isValidModule(moduleName))
			return false;
		SemanticVersion ver = findTypeVersion(moduleName, typeName, version);
		if (ver == null)
			return false;
		return storage.checkTypeSchemaRecordExists(moduleName, typeName, ver.toString());
	}

	private boolean isTypePresent(String moduleName, String typeName) throws TypeStorageException {
		ModuleInfo mi;
		try {
			mi = getModuleInfo(moduleName);
		} catch (NoSuchModuleException e) {
			return false;
		}
		return mi.getTypes().get(typeName) != null;
	}

	private SemanticVersion findTypeVersion(String moduleName, String typeName, 
			String versionText) throws TypeStorageException {
		return versionText == null ? findLastTypeVersion(moduleName, typeName, false) : 
			new SemanticVersion(versionText);
	}

	private SemanticVersion findLastTypeVersion(String moduleName, String typeName, 
			boolean withNoLongerSupported) throws TypeStorageException {
		if (!isTypePresent(moduleName, typeName))
			return null;
		ModuleInfo mi;
		try {
			mi = getModuleInfo(moduleName);
		} catch (NoSuchModuleException e) {
			return null;
		}
		return findLastTypeVersion(mi, typeName, withNoLongerSupported);
	}
	
	private SemanticVersion findLastTypeVersion(ModuleInfo module, String typeName, boolean withNoLongerSupported) {
		SemanticVersion ret = defaultVersion;
		TypeInfo ti = module.getTypes().get(typeName);
		if (ti == null || !(ti.isSupported() || withNoLongerSupported))
			return null;
		if (ti.getTypeVersion() != null && !ti.getTypeVersion().isEmpty())
			ret = new SemanticVersion(ti.getTypeVersion());
		return ret;
	}
	
	/**
	 * Given a moduleName, a typeName and version, return the JSON Schema document for the type. If 
	 * version parameter is null (no version number is specified) then the latest version is used for
	 * the schema returned if the underlying Json Schema database supports versioned typed objects.
	 * @param moduleName
	 * @param typeName
	 * @param version
	 * @return JSON Schema document as a String
	 * @throws NoSuchTypeException
	 */
	public String getJsonSchemaDocument(String moduleName, String typeName, String version)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		checkModule(moduleName);
		// first make sure that the json schema document can be found
		SemanticVersion schemaDocumentVer = findTypeVersion(moduleName, typeName, version);
		if (schemaDocumentVer == null)
			throwNoSuchTypeException(moduleName, typeName, null);
		String ret = storage.getTypeSchemaRecord(moduleName, typeName, schemaDocumentVer.toString());
		if (ret == null)
			throw new NoSuchTypeException("Unable to read type schema record: '"+moduleName+"."+typeName+"'");
		return ret;
	}

	protected void throwNoSuchTypeException(String moduleName, String typeName,
			String version) throws NoSuchTypeException {
		throw new NoSuchTypeException("Unable to locate type: '"+moduleName+"."+typeName+"'" + 
				(version == null ? "" : (" for version " + version)));
	}

	protected void throwNoSuchFuncException(String moduleName, String funcName,
			String version) throws NoSuchFuncException {
		throw new NoSuchFuncException("Unable to locate function: '"+moduleName+"."+funcName+"'" + 
				(version == null ? "" : (" for version " + version)));
	}
	
	public List<String> getAllRegisteredTypes(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		List<String> ret = new ArrayList<String>();
		for (TypeInfo typeInfo : getModuleInfo(moduleName).getTypes().values())
			if (typeInfo.isSupported())
				ret.add(typeInfo.getTypeName());
		return ret;
	}
	
	/**
	 * Return latest version of specified type. Version has two level structure of integers divided by dot like &lt;major&gt;.&lt;minor&gt;
	 * @param moduleName
	 * @param typeName
	 * @return latest version of specified type
	 */
	public String getLatestTypeVersion(String moduleName, String typeName) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		checkModule(moduleName);
		SemanticVersion ret = findLastTypeVersion(moduleName, typeName, false);
		if (ret == null)
			throwNoSuchTypeException(moduleName, typeName, null);
		return ret.toString();
	}
	
	private String saveType(ModuleInfo mi, String typeName, String jsonSchemaDocument,
			KbTypedef specParsing, boolean notBackwardCompatible, Set<RefInfo> dependencies) 
					throws NoSuchModuleException, TypeStorageException {
		TypeInfo ti = mi.getTypes().get(typeName);
		if (ti == null) {
			ti = new TypeInfo();
			ti.setTypeName(typeName);
			mi.getTypes().put(typeName, ti);
		}
		ti.setSupported(true);
		return saveType(mi, ti, jsonSchemaDocument, specParsing, notBackwardCompatible, dependencies);
	}
	
	private String saveType(ModuleInfo mi, TypeInfo ti, String jsonSchemaDocument,
			KbTypedef specParsing, boolean notBackwardCompatible, Set<RefInfo> dependencies) 
					throws NoSuchModuleException, TypeStorageException {
		SemanticVersion version = findLastTypeVersion(mi, ti.getTypeName(), true);
		if (version == null) {
			version = new SemanticVersion(0, 1);
		} else {
			int major = version.getMajor();
			int minor = version.getMinor();
			if (major > 0 && notBackwardCompatible) {
				major++;
				minor = 0;
			} else {
				minor++;
			}
			version = new SemanticVersion(major, minor);
		}
		ti.setTypeVersion(version.toString());
		return saveType(mi, ti, jsonSchemaDocument, specParsing, dependencies);
	}
	
	private String saveType(ModuleInfo mi, TypeInfo ti, String jsonSchemaDocument,
			KbTypedef specParsing, Set<RefInfo> dependencies) 
					throws NoSuchModuleException, TypeStorageException {
		if (dependencies != null)
			for (RefInfo ri : dependencies) {
				ri.setDepVersion(ti.getTypeVersion());
				updateInternalRefVersion(ri, mi);
			}
		storage.writeTypeSchemaRecord(mi.getModuleName(), ti.getTypeName(), ti.getTypeVersion(), jsonSchemaDocument);
		storage.removeTypeRefs(mi.getModuleName(), ti.getTypeName(), ti.getTypeVersion());
		writeTypeParsingFile(mi.getModuleName(), ti.getTypeName(), ti.getTypeVersion(), specParsing);
		return ti.getTypeVersion();
	}

	private void updateInternalRefVersion(RefInfo ri, ModuleInfo mi) {
		if (ri.getRefVersion() == null) {
			if (!ri.getRefModule().equals(mi.getModuleName()))
				throw new IllegalStateException("Type reference has no refVersion but reference is not internal: " + ri);
		}
		if (ri.getRefModule().equals(mi.getModuleName())) {
			TypeInfo ti = mi.getTypes().get(ri.getRefName());
			if (ti == null)
				throw new IllegalStateException("Type reference was not found: " + ri);
			ri.setRefVersion(ti.getTypeVersion());
		}
	}
	
	private void writeTypeParsingFile(String moduleName, String typeName, String version, 
			KbTypedef document) throws TypeStorageException {
		try {
			StringWriter sw = new StringWriter();
			mapper.writeValue(sw, document.getData());
			sw.close();
			storage.writeTypeParseRecord(moduleName, typeName, version, sw.toString());
		} catch (IOException ex) {
			throw new IllegalStateException("Unexpected internal error: " + ex.getMessage(), ex);
		}
	}
	
	/**
	 * Change major version from 0 to 1.
	 * @param moduleName
	 * @param typeName
	 * @param userId
	 * @return new version
	 * @throws NoSuchTypeException when current major version isn't 0
	 */
	public String releaseType(String moduleName, String typeName, String userId)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		ModuleInfo info = getModuleInfo(moduleName);
		SemanticVersion curVersion = findLastTypeVersion(info, typeName, false);
		if (curVersion == null)
			throwNoSuchTypeException(moduleName, typeName, null);
		if (curVersion.getMajor() != 0)
			throwNoSuchTypeException(moduleName, typeName, "0.x");
		String jsonSchemaDocument = getJsonSchemaDocument(moduleName, typeName);
		KbTypedef specParsing = getTypeParsingDocument(moduleName, typeName);
		Set<RefInfo> deps = storage.getTypeRefsByDep(moduleName, typeName, curVersion.toString());
		SemanticVersion ret = releaseVersion;
		long transactionStartTime = storage.getStorageCurrentTime();
		try {
			TypeInfo ti = info.getTypes().get(typeName);
			ti.setTypeVersion(ret.toString());
			writeModuleInfo(moduleName, info, transactionStartTime);
			saveType(info, ti, jsonSchemaDocument, specParsing, deps);
			storage.addRefs(deps, new TreeSet<RefInfo>());
			transactionStartTime = -1;
		} finally {
			if (transactionStartTime > 0)
				rollbackModuleTransaction(moduleName, transactionStartTime);
		}
		return ret.toString();
	}
	
	public void removeTypeForAllVersions(String moduleName, String typeName, String userId)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		ModuleInfo info = getModuleInfo(moduleName);
		if (!info.getTypes().containsKey(typeName))
			throwNoSuchTypeException(moduleName, typeName, null);
		info.getTypes().remove(typeName);
		writeModuleInfo(moduleName, info, storage.getStorageCurrentTime());
		storage.removeAllTypeRecords(moduleName, typeName);
	}

	public void removeFuncForAllVersions(String moduleName, String funcName, String userId)
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		ModuleInfo info = getModuleInfo(moduleName);
		if (!info.getFuncs().containsKey(funcName))
			throwNoSuchFuncException(moduleName, funcName, null);
		info.getTypes().remove(funcName);
		writeModuleInfo(moduleName, info, storage.getStorageCurrentTime());
		storage.removeAllFuncRecords(moduleName, funcName);
	}
	
	/**
	 * Given a moduleName, a typeName and version, return the JSON Schema document for the type. If 
	 * version parameter is null (no version number is specified) then the latest version of document will be returned.
	 * @param moduleName
	 * @param typeName
	 * @param version
	 * @return JSON Schema document as a String
	 * @throws NoSuchTypeException
	 */
	public KbTypedef getTypeParsingDocument(String moduleName, String typeName,
			String version) throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		checkModule(moduleName);
		SemanticVersion documentVer = findTypeVersion(moduleName, typeName, version);
		if (documentVer == null)
			throwNoSuchTypeException(moduleName, typeName, null);
		String ret = storage.getTypeParseRecord(moduleName, typeName, documentVer.toString());
		if (ret == null)
			throw new NoSuchTypeException("Unable to read type parse record: '"+moduleName+"."+typeName+"'");
		try {
			Map<?,?> data = mapper.readValue(ret, Map.class);
			return new KbTypedef().loadFromMap(data);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
		
	private void rollbackModuleTransaction(String moduleName, long transtactionStartTime) {
	}
	
	private void writeModuleInfo(String moduleName, ModuleInfo info, long backupTime) throws TypeStorageException {
		if (storage.checkModuleInfoRecordExist(moduleName)) {
			ModuleInfo prevInfo = storage.getModuleInfoRecord(moduleName);
			storage.writeModuleInfoRecordBackup(moduleName, prevInfo, backupTime);
		}
		storage.writeModuleInfoRecord(moduleName, info);
	}

	private void writeModuleSpec(String moduleName, String specDocument, long backupTime) throws TypeStorageException {
		if (storage.checkModuleSpecRecordExist(moduleName)) {
			String prevText = storage.getModuleSpecRecord(moduleName);
			storage.writeModuleSpecRecordBackup(moduleName, prevText, backupTime);
		}
		storage.writeModuleSpecRecord(moduleName, specDocument);
	}

	public String getModuleSpecDocument(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		checkModule(moduleName);
		return storage.getModuleSpecRecord(moduleName);
	}
	
	public ModuleInfo getModuleInfo(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		checkModuleRegistered(moduleName);
		return storage.getModuleInfoRecord(moduleName);
	}
		
	public List<String> getAllRegisteredFuncs(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		List<String> ret = new ArrayList<String>();
		for (FuncInfo info : getModuleInfo(moduleName).getFuncs().values()) 
			if (info.isSupported())
				ret.add(info.getFuncName());
		return ret;
	}

	private SemanticVersion findLastFuncVersion(String moduleName, String funcName, 
			boolean withNotSupported) throws TypeStorageException {
		try {
			return findLastFuncVersion(getModuleInfo(moduleName), funcName, withNotSupported);
		} catch (NoSuchModuleException e) {
			return null;
		}
	}
		
	private SemanticVersion findLastFuncVersion(ModuleInfo mi, String funcName, boolean withNotSupported) {
		SemanticVersion ret = defaultVersion;
		FuncInfo fi = mi.getFuncs().get(funcName);
		if (fi == null || !(fi.isSupported() || withNotSupported))
			return null;
		if (fi.getFuncVersion() != null && !fi.getFuncVersion().isEmpty())
			ret = new SemanticVersion(fi.getFuncVersion());
		return ret;
	}

	/**
	 * Return latest version of specified type. Version has two level structure of integers divided by dot like &lt;major&gt;.&lt;minor&gt;
	 * @param moduleName
	 * @param funcName
	 * @return latest version of specified type
	 */
	public String getLatestFuncVersion(String moduleName, String funcName)
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		checkModule(moduleName);
		SemanticVersion ret = findLastFuncVersion(moduleName, funcName, false);
		if (ret == null)
			throwNoSuchFuncException(moduleName, funcName, null);
		return ret.toString();
	}
	
	private String saveFunc(ModuleInfo mi, String funcName, KbFuncdef specParsingDocument, 
			boolean notBackwardCompatible, Set<RefInfo> dependencies) throws NoSuchModuleException, TypeStorageException {
		FuncInfo fi = mi.getFuncs().get(funcName);
		if (fi == null) {
			fi = new FuncInfo();
			fi.setFuncName(funcName);
			mi.getFuncs().put(funcName, fi);
		}
		fi.setSupported(true);
		return saveFunc(mi, fi, specParsingDocument, notBackwardCompatible, dependencies);
	}
	
	private String saveFunc(ModuleInfo mi, FuncInfo fi, KbFuncdef specParsingDocument, 
			boolean notBackwardCompatible, Set<RefInfo> dependencies) throws NoSuchModuleException, TypeStorageException {
		SemanticVersion version = findLastFuncVersion(mi, fi.getFuncName(), true);
		if (version == null) {
			version = new SemanticVersion(0, 1);
		} else {
			int major = version.getMajor();
			int minor = version.getMinor();
			if (major > 0 && notBackwardCompatible) {
				major++;
				minor = 0;
			} else {
				minor++;
			}
			version = new SemanticVersion(major, minor);
		}
		fi.setFuncVersion(version.toString());
		return saveFunc(mi, fi, specParsingDocument, dependencies);
	}
		
	private String saveFunc(ModuleInfo mi, FuncInfo fi, KbFuncdef specParsingDocument, 
			Set<RefInfo> dependencies) throws NoSuchModuleException, TypeStorageException {
		if (dependencies != null)
			for (RefInfo dep : dependencies) {
				dep.setDepVersion(fi.getFuncVersion());
				updateInternalRefVersion(dep, mi);
			}
		writeFuncParsingFile(mi.getModuleName(), fi.getFuncName(), fi.getFuncVersion(), specParsingDocument);
		return fi.getFuncVersion();
	}
	
	private void writeFuncParsingFile(String moduleName, String funcName, String version, KbFuncdef document) 
			throws TypeStorageException {
		try {
			StringWriter sw = new StringWriter();
			mapper.writeValue(sw, document.getData());
			sw.close();
			storage.writeFuncParseRecord(moduleName, funcName, version.toString(), sw.toString());
		} catch (TypeStorageException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new IllegalStateException("Unexpected internal error: " + ex.getMessage(), ex);
		}
	}

	public KbFuncdef getFuncParsingDocument(String moduleName, String funcName) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		return getFuncParsingDocument(moduleName, funcName, null);
	}

	/**
	 * Change major version from 0 to 1.
	 * @param moduleName
	 * @param funcName
	 * @return new version
	 * @throws NoSuchTypeException when current major version isn't 0
	 */
	public String releaseFunc(String moduleName, String funcName, String userId) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		SemanticVersion curVersion = findLastFuncVersion(moduleName, funcName, false);
		if (curVersion == null)
			throwNoSuchFuncException(moduleName, funcName, null);
		if (curVersion.getMajor() != 0)
			throwNoSuchFuncException(moduleName, funcName, "0.x");
		KbFuncdef specParsing = getFuncParsingDocument(moduleName, funcName);
		Set<RefInfo> deps = storage.getFuncRefsByDep(moduleName, funcName, curVersion.toString());
		ModuleInfo info = getModuleInfo(moduleName);
		SemanticVersion ret = releaseVersion;
		long transactionStartTime = storage.getStorageCurrentTime();
		try {
			FuncInfo fi = info.getFuncs().get(funcName);
			fi.setFuncVersion(ret.toString());
			writeModuleInfo(moduleName, info, transactionStartTime);
			saveFunc(info, fi, specParsing, deps);
			storage.addRefs(new TreeSet<RefInfo>(), deps);
			transactionStartTime = -1;
		} finally {
			if (transactionStartTime > 0)
				rollbackModuleTransaction(moduleName, transactionStartTime);
		}
		return ret.toString();
	}
	
	public KbFuncdef getFuncParsingDocument(String moduleName, String typeName,
			String version) throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		checkModule(moduleName);
		SemanticVersion curVersion = version == null ? 
				findLastFuncVersion(moduleName, typeName, false) : new SemanticVersion(version);
		if (curVersion == null)
			throwNoSuchFuncException(moduleName, typeName, null);
		String ret = storage.getFuncParseRecord(moduleName, typeName, curVersion.toString());
		if (ret == null)
			throwNoSuchFuncException(moduleName, typeName, version);
		try {
			Map<?,?> data = mapper.readValue(ret, Map.class);
			return new KbFuncdef().loadFromMap(data);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
	
	private void stopTypeSupport(ModuleInfo mi, String typeName) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		TypeInfo ti = mi.getTypes().get(typeName);
		if (ti == null)
			throwNoSuchTypeException(mi.getModuleName(), typeName, null);
		String jsonSchemaDocument = storage.getTypeSchemaRecord(mi.getModuleName(), typeName, ti.getTypeVersion());
		KbTypedef specParsing = getTypeParsingDocument(mi.getModuleName(), typeName, ti.getTypeVersion());
		saveType(mi, ti, jsonSchemaDocument, specParsing, false, null);
		ti.setSupported(false);
	}
	
	public void stopTypeSupport(String moduleName, String typeName, String userId)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		ModuleInfo mi = getModuleInfo(moduleName);
		long transactionStartTime = storage.getStorageCurrentTime();
		try {
			writeModuleInfo(moduleName, mi, transactionStartTime);
			stopTypeSupport(mi, typeName);
			transactionStartTime = -1;
		} finally {
			if (transactionStartTime > 0)
				rollbackModuleTransaction(moduleName, transactionStartTime);
		}
	}
	
	public void removeTypeVersion(String moduleName, String typeName, String version, String userId) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		checkModule(moduleName);
		SemanticVersion sVer = new SemanticVersion(version);
		if (!storage.removeTypeRecordsForVersion(moduleName, typeName, sVer.toString()))
			throwNoSuchTypeException(moduleName, typeName, version);
	}
	
	private void stopFuncSupport(ModuleInfo info, String funcName) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		FuncInfo fi = info.getFuncs().get(funcName);
		if (fi == null)
			throwNoSuchFuncException(info.getModuleName(), funcName, null);
		KbFuncdef specParsingDocument = getFuncParsingDocument(info.getModuleName(), funcName, fi.getFuncVersion());
		saveFunc(info, fi, specParsingDocument, false, null);
		fi.setSupported(false);
	}
	
	public void stopFuncSupport(String moduleName, String funcName, String userId)
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		ModuleInfo mi = getModuleInfo(moduleName);
		long transactionStartTime = storage.getStorageCurrentTime();
		try {
			writeModuleInfo(moduleName, mi, transactionStartTime);
			stopFuncSupport(mi, funcName);
			transactionStartTime = -1;
		} finally {
			if (transactionStartTime > 0)
				rollbackModuleTransaction(moduleName, transactionStartTime);
		}
	}
	
	public void removeModule(String moduleName, String userId) throws NoSuchModuleException, TypeStorageException {
		// TODO: check userId
		checkModuleRegistered(moduleName);
		storage.removeModule(moduleName);
	}
	
	/**
	 * @return all names of registered modules
	 */
	public List<String> getAllRegisteredModules() throws TypeStorageException {
		return storage.getAllRegisteredModules();
	}
	
	public void removeAllRefs(String userId) throws TypeStorageException {
		// TODO: check userId
		storage.removeAllRefs();
	}
	
	public Set<RefInfo> getTypeRefsByDep(String depModule, String depType, 
			String version) throws TypeStorageException {
		return storage.getTypeRefsByDep(depModule, depType, version);
	}
	
	public Set<RefInfo> getTypeRefsByRef(String refModule, String refType, 
			String version) throws TypeStorageException {
		return storage.getTypeRefsByRef(refModule, refType, version);
	}
	
	public Set<RefInfo> getFuncRefsByDep(String depModule, String depFunc, 
			String version) throws TypeStorageException {
		return storage.getFuncRefsByDep(depModule, depFunc, version);
	}
	
	public Set<RefInfo> getFuncRefsByRef(String refModule, String refType, 
			String version) throws TypeStorageException {
		return storage.getFuncRefsByRef(refModule, refType, version);
	}
	
	private File createTempDir() {
		File ret = new File(parentTempDir, "temp_" + System.currentTimeMillis());
		ret.mkdirs();
		return ret;
	}
	
	public void requestModuleRegistration(String moduleName, String ownerUserId)
			throws TypeStorageException {
		if (!withApprovalQueue)
			throw new IllegalStateException("Type definition db was created without approval queue, please use registerModule without request.");
		autoGenerateModuleInfo(moduleName, ownerUserId);  // TODO: it should be saved in special queue instead
	}
	
	private void autoGenerateModuleInfo(String moduleName, String ownerUserId) throws TypeStorageException {
		if (storage.checkModuleInfoRecordExist(moduleName))
			throw new IllegalStateException("Module " + moduleName + " was already registered");
		ModuleInfo info = new ModuleInfo();
		info.setModuleName(moduleName);
		info.setOwner(ownerUserId);
		storage.writeModuleInfoRecord(moduleName, info);
	}
	
	public void registerModule(String specDocument, List<String> registeredTypes, 
			String userId) throws SpecParseException, TypeStorageException {
		saveModule(specDocument, true, new HashSet<String>(registeredTypes), Collections.<String>emptySet(), 
				Collections.<String>emptySet(), Collections.<String>emptySet(), true, userId);
	}
	
	private String correctSpecIncludes(String specDocument, List<String> includedModules) throws SpecParseException {
		try {
			StringWriter withGoodImports = new StringWriter();
			PrintWriter pw = null; // new PrintWriter(withGoodImports);
			BufferedReader br = new BufferedReader(new StringReader(specDocument));
			while (true) {
				String l = br.readLine();
				if (l == null)
					break;
				if (pw == null) {
					if (l.trim().isEmpty())
						continue;
					if (l.startsWith("#include")) {
						l = l.substring(8).trim();
						if (!(l.startsWith("<") && l.endsWith(">")))
							throw new IllegalStateException("Wrong include structure (it should be ...<file_path>): " + l);
						l = l.substring(1, l.length() - 1).trim();
						if (l.indexOf('/') >= 0)
							l = l.substring(l.lastIndexOf('/') + 1);
						if (l.indexOf('.') >= 0)
							l = l.substring(0, l.indexOf('.')).trim();
						includedModules.add(l);
					} else {
						pw = new PrintWriter(withGoodImports);
						for (String iModuleName : includedModules)
							pw.println("#include <" + iModuleName + ".types>");
						pw.println();
						pw.println(l);
					}
				} else {
					pw.println(l);
				}
			}
			br.close();
			pw.close();
			return withGoodImports.toString();
		} catch (Exception ex) {
			throw new SpecParseException("Unexpected error during parsing of spec-file include declarations: " + ex.getMessage(), ex);
		}
	}
	
	private KbModule compileSpecFile(String specDocument, List<String> includedModules,
			Map<String, Map<String, String>> moduleToTypeToSchema,
			Map<String, ModuleInfo> moduleToInfo) throws SpecParseException {
		File tempDir = createTempDir();
		try {
			File specFile = new File(tempDir, "currentlyCompiled.spec");
			writeFile(specDocument, specFile);
			for (String iModule : includedModules) {
				saveIncludedModuleRecusive(tempDir, iModule, moduleToInfo);
			}
			List<KbService> services = KidlParser.parseSpec(specFile, tempDir, moduleToTypeToSchema);
			if (services.size() != 1)
				throw new SpecParseException("Spec-file should consist of only one service");
			if (services.get(0).getModules().size() != 1)
				throw new SpecParseException("Spec-file should consist of only one module");
			return services.get(0).getModules().get(0);
		} catch (SpecParseException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SpecParseException("Unexpected error during spec-file parsing: " + ex.getMessage(), ex);			
		} finally {
			deleteTempDir(tempDir);
		}
	}
	
	private void saveModule(String specDocument, boolean isNew, Set<String> changedTypes,
			Set<String> backwardIncompatibleTypes, Set<String> changedFuncs,
			Set<String> backwardIncompatibleFuncs, boolean useAllFuncsInstead,
			String userId) throws SpecParseException, TypeStorageException {
		List<String> includedModules = new ArrayList<String>();
		specDocument = correctSpecIncludes(specDocument, includedModules);
		//System.out.println("----------------------------------------------");
		//System.out.println("Spec-file:");
		//System.out.println(specDocument);
		String moduleName = null;
		long transactionStartTime = -1;
		try {
			Map<String, Map<String, String>> moduleToTypeToSchema = new HashMap<String, Map<String, String>>();
			Map<String, ModuleInfo> moduleToInfo = new HashMap<String, ModuleInfo>();
			KbModule module = compileSpecFile(specDocument, includedModules, moduleToTypeToSchema, moduleToInfo);
			moduleName = module.getModuleName();
			if (isNew) {
				if (withApprovalQueue) {
					checkModuleRegistered(moduleName);
					if (storage.checkModuleSpecRecordExist(moduleName))
						throw new IllegalStateException("Module " + moduleName + " was already uploaded");
				} else {
					if (!storage.checkModuleInfoRecordExist(moduleName))
						autoGenerateModuleInfo(moduleName, userId);
				}
			} else {
				checkModule(moduleName);
			}
			ModuleInfo info = getModuleInfo(moduleName);
			if (!uip.isAdmin(userId)) {
				if (!info.getOwner().equals(userId))
					throw new SpecParseException("Module owner is not in updating user list: " +
							"owner=" + info.getOwner() + ", changing_user=" + userId);
			}
			info.setIncludedModuleNames(new ArrayList<String>(includedModules));
			Map<String, String> typeToSchema = moduleToTypeToSchema.get(moduleName);
			if (typeToSchema == null)
				throw new SpecParseException("Json schema generation was missed for module: " + moduleName);
			List<KbTypedef> typesToSave = new ArrayList<KbTypedef>();
			Set<String> registeredTypes = new HashSet<String>();
			Set<String> registeredFuncs = new HashSet<String>();
			if (!isNew) {
				for (TypeInfo typeInfo : info.getTypes().values())
					if (typeInfo.isSupported())
						registeredTypes.add(typeInfo.getTypeName());
				registeredFuncs.addAll(getAllRegisteredFuncs(moduleName));
				for (FuncInfo funcInfo : getModuleInfo(moduleName).getFuncs().values()) 
					if (funcInfo.isSupported())
						registeredFuncs.add(funcInfo.getFuncName());
			}
			registeredTypes.addAll(changedTypes);
			registeredFuncs.addAll(changedFuncs);
			Set<String> allNewTypes = new HashSet<String>();
			Set<String> allNewFuncs = new HashSet<String>();
			List<ComponentCreation> comps = new ArrayList<ComponentCreation>();
			for (KbModuleComp comp : module.getModuleComponents()) {
				if (comp instanceof KbTypedef) {
					KbTypedef type = (KbTypedef)comp;
					allNewTypes.add(type.getName());
					if (changedTypes.contains(type.getName())) {
						if (typeToSchema.get(type.getName()) == null)
							throw new SpecParseException("Json schema wasn't generated for type: " + type.getName());
						typesToSave.add(type);
						String jsonSchemaDocument = typeToSchema.get(type.getName());
						boolean notBackwardCompatible = backwardIncompatibleTypes.contains(type.getName());
						Set<RefInfo> dependencies = extractTypeRefs(type, moduleToInfo, registeredTypes);
						jsonSchemaFromString(info.getModuleName(), type.getName(), jsonSchemaDocument);
						comps.add(new ComponentCreation(true, type.getName(), jsonSchemaDocument, type, null, 
								notBackwardCompatible, dependencies));
					}
				} else if (comp instanceof KbFuncdef) {
					KbFuncdef func = (KbFuncdef)comp;
					allNewFuncs.add(func.getName());
					if (isNew || changedFuncs.contains(func.getName())) {
						boolean notBackwardCompatible = backwardIncompatibleFuncs.contains(func.getName());
						Set<RefInfo> dependencies = new TreeSet<RefInfo>();
						for (KbParameter param : func.getParameters())
							dependencies.addAll(extractTypeRefs(moduleName, func.getName(), param, moduleToInfo, registeredTypes));
						for (KbParameter param : func.getReturnType())
							dependencies.addAll(extractTypeRefs(moduleName, func.getName(), param, moduleToInfo, registeredTypes));					
						comps.add(new ComponentCreation(false, func.getName(), null, null, func, notBackwardCompatible, 
								dependencies));
					}
				}
			}
			Set<RefInfo> createdTypeRefs = new TreeSet<RefInfo>();
			Set<RefInfo> createdFuncRefs = new TreeSet<RefInfo>();
			transactionStartTime = storage.getStorageCurrentTime();
			
			for (ComponentCreation comp : comps) {
				if (comp.isType) {
					saveType(info, comp.name, comp.jsonSchemaDocument, comp.typeParsing, comp.notBackwardCompatible, 
							comp.dependencies);					
					createdTypeRefs.addAll(comp.dependencies);
				} else {
					saveFunc(info, comp.name, comp.funcParsing, comp.notBackwardCompatible, comp.dependencies);
					createdFuncRefs.addAll(comp.dependencies);
				}
			}
			writeModuleInfo(moduleName, info, transactionStartTime);
			writeModuleSpec(moduleName, specDocument, transactionStartTime);
			for (String typeName : registeredTypes) {
				if (!allNewTypes.contains(typeName)) {
					if (changedTypes.contains(typeName))
						throw new SpecParseException("Type from change list is not found in current spec-file: " + typeName);
					stopTypeSupport(info, typeName);
				}
			}
			for (String funcName : registeredFuncs) {
				if (!allNewFuncs.contains(funcName)) {
					if (changedFuncs.contains(funcName))
						throw new SpecParseException("Function from change list is not found in current spec-file: " + funcName);
					stopFuncSupport(info, funcName);
				}
			}
			storage.addRefs(createdTypeRefs, createdFuncRefs);
			transactionStartTime = -1;
		} catch (TypeStorageException ex) {
			throw ex;
		} catch (SpecParseException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SpecParseException("Unexpected error during spec-file parsing: " + ex.getMessage(), ex);			
		} finally {
			try {
				if (transactionStartTime > 0) {
					rollbackModuleTransaction(moduleName, transactionStartTime);
				}
			} catch (Exception ignore) {}
		}
	}

	private Set<RefInfo> extractTypeRefs(KbTypedef main, Map<String, ModuleInfo> moduleToInfo,
			Set<String> mainRegisteredTypes) throws SpecParseException {
		Set<RefInfo> ret = new TreeSet<RefInfo>();
		collectTypeRefs(ret, main.getModule(), main.getName(), main.getAliasType(), moduleToInfo, mainRegisteredTypes);
		return ret;
	}

	private Set<RefInfo> extractTypeRefs(String module, String funcName, KbParameter main, 
			Map<String, ModuleInfo> moduleToInfo, Set<String> mainRegisteredTypes)
					throws SpecParseException {
		Set<RefInfo> ret = new TreeSet<RefInfo>();
		collectTypeRefs(ret, module, funcName, main.getType(), moduleToInfo, mainRegisteredTypes);
		return ret;
	}

	private void collectTypeRefs(Set<RefInfo> ret, String mainModule, String mainName, KbType internal, 
			Map<String, ModuleInfo> moduleToInfo, Set<String> mainRegisteredTypes) 
					throws SpecParseException {
		if (internal instanceof KbTypedef) {
			KbTypedef type = (KbTypedef)internal;
			boolean isOuterModule = !type.getModule().equals(mainModule);
			boolean terminal = isOuterModule || mainRegisteredTypes.contains(type.getName());
			if (terminal) {
				RefInfo ref = new RefInfo();
				ref.setDepModule(mainModule);
				ref.setDepName(mainName);
				ref.setRefModule(type.getModule());
				ref.setRefName(type.getName());
				if (isOuterModule) {
					ModuleInfo oModule = moduleToInfo.get(type.getModule());
					TypeInfo oType = null;
					if (oModule != null)
						oType = oModule.getTypes().get(type.getName());
					if (oType == null)
						throw new SpecParseException("Reference to external not registered " +
								"module/type is missing: " + type.getModule() + "." + type.getName());
					ref.setRefVersion(oType.getTypeVersion());
				}
				ret.add(ref);
			} else {
				collectTypeRefs(ret, mainModule, mainName, type.getAliasType(), moduleToInfo, mainRegisteredTypes);
			}
		} else if (internal instanceof KbList) {
			KbList type = (KbList)internal;
			collectTypeRefs(ret, mainModule, mainName, type.getElementType(), moduleToInfo, mainRegisteredTypes);
		} else if (internal instanceof KbMapping) {
			KbMapping type = (KbMapping)internal;
			collectTypeRefs(ret, mainModule, mainName, type.getValueType(), moduleToInfo, mainRegisteredTypes);
		} else if (internal instanceof KbStruct) {
			KbStruct type = (KbStruct)internal;
			for (KbStructItem item : type.getItems())
				collectTypeRefs(ret, mainModule, mainName, item.getItemType(), moduleToInfo, mainRegisteredTypes);				
		} else if (internal instanceof KbTuple) {
			KbTuple type = (KbTuple)internal;
			for (KbType iType : type.getElementTypes())
				collectTypeRefs(ret, mainModule, mainName, iType, moduleToInfo, mainRegisteredTypes);				
		}
	}
	
	private void saveIncludedModuleRecusive(File workDir, String moduleName, Map<String, ModuleInfo> savedModules) 
			throws NoSuchModuleException, IOException, TypeStorageException {
		if (savedModules.containsKey(moduleName))
			return;
		String spec = getModuleSpecDocument(moduleName);
		writeFile(spec, new File(workDir, moduleName + ".types"));
		ModuleInfo info = getModuleInfo(moduleName);
		savedModules.put(moduleName, info);
		for (String included : info.getIncludedModuleNames())
			saveIncludedModuleRecusive(workDir, included, savedModules);
	}
	
	private static void writeFile(String text, File f) throws IOException {
		FileWriter fw = new FileWriter(f);
		fw.write(text);
		fw.close();
	}
	
	private void deleteTempDir(File dir) {
		for (File f : dir.listFiles()) {
			if (f.isFile()) {
				f.delete();
			} else {
				deleteTempDir(f);
			}
		}
		dir.delete();
	}
	
	public void updateModule(String specDocument, List<String> changedTypes,
			List<String> backwardIncompatibleTypes, List<String> changedFuncs,
			List<String> backwardIncompatibleFuncs, String userId) throws SpecParseException, TypeStorageException {
		saveModule(specDocument, false, new HashSet<String>(changedTypes), 
				new HashSet<String>(backwardIncompatibleTypes), new HashSet<String>(changedFuncs), 
				new HashSet<String>(backwardIncompatibleFuncs), false, userId);
		
	}
	
	private static class ComponentCreation {
		boolean isType;
		String name;
		String jsonSchemaDocument;
		KbTypedef typeParsing;
		KbFuncdef funcParsing;
		boolean notBackwardCompatible;
		Set<RefInfo> dependencies;
		
		public ComponentCreation(boolean isType, String name, String jsonSchemaDocument, KbTypedef typeParsing,
				KbFuncdef funcParsing, boolean notBackwardCompatible, Set<RefInfo> dependencies) {
			this.isType = isType;
			this.name = name;
			this.jsonSchemaDocument = jsonSchemaDocument;
			this.typeParsing = typeParsing;
			this.funcParsing = funcParsing;
			this.notBackwardCompatible = notBackwardCompatible;
			this.dependencies = dependencies;
		}
	}
}
