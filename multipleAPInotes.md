This branch is a proof of concept, and POC only, of running two different
workspace APIs at the same time in the same glassfish application. Running
multiple APIs means we can make backwards incompatible changes to the workspace
API and add an API version, leaving the old API alone (until presumably
deprecated and removed). It's essentially untested.

Notes:
* The code sucks. It should be done much more elegantly - basically any files
  that aren't generated by the type compiler (TC) should be completely unaware
  of any TC files, since they specify the API and change from version to 
  version. The rest of the code should not have API versions.
* Probably need to make a singleton logger and make sure it really is a
  singleton across both API servers. Also there's no way to tell which version
  of the API was called from the log (which may not be an issue, really).
* The API Servlets are independent regarding startup - one can succeed and the
  other fail. Probably want to do something about this.
* Probably need a singleton TempDirManager as well + verification that the
  singleton pattern works across both API Servlets.
  
Example usage:

	~:~/localgit/workspace_deluxe/lib$ ipython 
	In [1]: from biokbase.workspace.client import Workspace
	In [2]: import json
	In [3]: import requests
	In [4]: wsurl = 'http://localhost:7058'
	In [5]: ws1url = wsurl + '/api/v1'
	In [6]: ws2url = wsurl + '/api/v2'
	In [7]: wsstd = Workspace(wsurl, user_id='kbasetest', password='foo')
	In [8]: ws1 = Workspace(ws1url, user_id='kbasetest', password='foo')
	In [9]: ws2 = Workspace(ws2url, user_id='kbasetest', password='foo')
	
	In [10]: wsstd.ver()
	Out[10]: u'0.3.5 API1'
	In [11]: ws1.ver()
	Out[11]: u'0.3.5 API1'
	In [12]: ws2.ver()
	Out[12]: u'0.3.5 API2'
	
	In [13]: args = {'method': None, 'params': None, 'version': '1.1', 'id': 151246616416}
	
	In [14]: args['method'] = 'Workspace.get_workspacemeta'
	In [15]: args['params'] = [{'workspace': 'gavinws'}]
	
	In [16]: requests.post(wsurl, data=json.dumps(args), headers=wsstd._headers).content
	Out[16]: '{"version":"1.1","result":[["gavinws","kbasetest","2015-11-12T00:32:51+0000",0,"a","n",2]]}'
	
	In [17]: requests.post(ws1url, data=json.dumps(args), headers=wsstd._headers).content
	Out[17]: '{"version":"1.1","result":[["gavinws","kbasetest","2015-11-12T00:32:51+0000",0,"a","n",2]]}'
	
	In [18]: requests.post(ws2url, data=json.dumps(args), headers=wsstd._headers).content
	Out[18]: '{"version":"1.1","error":{"name":"JSONRPCError","code":-32601,
	"message":"Can not find method [Workspace.get_workspacemeta] in server class us.kbase.workspace.api.v2.workspace.WorkspaceServer","error":null},"id":"151246616416"}'
	
	In [19]: args['method'] = 'Workspace.a_v2_function'
	In [20]: args['params'] = []
	
	In [21]: requests.post(wsurl, data=json.dumps(args), headers=wsstd._headers).content
	Out[21]: '{"version":"1.1","error":{"name":"JSONRPCError","code":-32601,
	"message":"Can not find method [Workspace.a_v2_function] in server class us.kbase.workspace.api.v1.workspace.WorkspaceServer","error":null},"id":"151246616416"}'
	
	In [22]: requests.post(ws1url, data=json.dumps(args), headers=wsstd._headers).content
	Out[22]: '{"version":"1.1","error":{"name":"JSONRPCError","code":-32601,
	"message":"Can not find method [Workspace.a_v2_function] in server class us.kbase.workspace.api.v1.workspace.WorkspaceServer","error":null},"id":"151246616416"}'
	
	In [23]: requests.post(ws2url, data=json.dumps(args), headers=wsstd._headers).content
	Out[23]: '{"version":"1.1","result":["Good heavens, an alternate API in the same service!"]}'