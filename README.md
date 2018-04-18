# repository-cleaner-maven-plugin

repository-cleaner-maven-plugin cleans the local maven repository from old versions and/or versions' old builds.

Running this tool will preserve the latest version of every artifact and remove the other versions.  
If not explicitly configured there won't be any changes made to the repository and no deletions will take place!

##Whitelist, blacklist, preserve latest list
A version can be excluded from getting removed or included in designating for deletion by putting it to the whitelist or blacklist.
###Syntax
    [[<group>:]<artifact>:]<version>
where <code>&lt;group&gt;</code>, <code>&lt;artifact&gt;</code> and <code>&lt;version&gt;</code> can contain the wildcards '?' and '*'.

Examples
* <code>1.0</code> (version 1.0 of every artifact)
* <code>com.example:myartifact:1.0-SNAPSHOT</code> (version "1.0-SNAPSHOT" of artifact "myartifact" in group "com.example")
* <code>com.example.\*:\*:1.\*</code> (all artifacts within a group beginning with "com.example." and version beginning with "1.")

Furthermore the latest version(s) of an artifact can be defined to be excluded from getting deleted. For example the latest version 2.x of an artifact has to be preserved. This can be achieved by adding an entry to the preserve latest list with the same syntax as above. An entry usualy contains a wildcard at least for the version. The latest version of all correspondig versions is preserved, all others are designated for deletion. This is applied individually to all artifacts that correspond to <code>&lt;artifact&gt;</code> and <code>&lt;version&gt;</code> if applied.

Examples
* <code>*-SNAPSHOT</code> (preserve the latest snapshot version of every artifact)</li>
* <code>com.example.\*:\*:2.\*</code> (preserve all latest versions 2.x of all artifacts within a group beginning with "com.example.")</li>

####Precendence
* Whitelist. An artifact's version corresponding to any whitelist entry won't be removed.</li>
* Preserve latest version list. The lastest version corresponding to any of its entries won't be removed.</li>
* Blacklist. A version corresponding to any blacklist entry will be designated for deletion if it is not matched by an entry in a list above.</li>
* Preserve latest. A version will be designated for deletion if it is not the latest version of its artifact and not matched by an entry in a list above.</li>
