package us.kbase.workspace.database;

import java.util.Date;



public interface WorkspaceMetaData {
	
	public long getId();
	public String getName();
	public WorkspaceUser getOwner();
	public Date getModDate();
	public Permission getUserPermission();
	public boolean isGloballyReadable();
}
