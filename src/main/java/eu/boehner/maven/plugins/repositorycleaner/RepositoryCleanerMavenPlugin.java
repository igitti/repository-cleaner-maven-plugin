package eu.boehner.maven.plugins.repositorycleaner;
import java.io.File;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Cleans the local maven repository from old versions and/or versions' old builds.<br/>
 * <br/>
 * Running this tool will preserve the latest version of every artifact and remove the other versions.<br/>
 * If not explicitly configured there won't be any changes made to the repository and no deletions will take place!
 * ({@link #deleteBuilds}, {@link #deleteVersions})<br/>
 * 
 * <h2>Whitelist, blacklist, preserve latest list</h2>
 * A version can be excluded from getting removed or included in designating for deletion by putting it to the {@link #whitelist} or {@link #blacklist}.
 * <h3>Syntax</h3>
 * <code>&lt;entry&gt; ::= [[&lt;group&gt;:]&lt;artifact&gt;:]&lt;version&gt;</code><br/>
 * where <code>&lt;group&gt;</code>, <code>&lt;artifact&gt;</code> and <code>&lt;version&gt;</code> can contain the wildcards '?' and '*'.<br/>
 * Examples
 * <ul>
 * <li><code>1.0</code> (version 1.0 of every artifact)</li>
 * <li><code>com.example:myartifact:1.0-SNAPSHOT</code> (version "1.0-SNAPSHOT" of artifact "myartifact" in group "com.example")</li>
 * <li><code>com.example.*:*:1.*</code> (all artifacts within a group beginning with "com.example." and version beginning with "1.")</li>
 * </ul>
 * Furthermore the latest version(s) of an artifact can be defined to be excluded from getting deleted. For example the latest version 2.x of an artifact has to be preserved.<br/>
 * This can be achieved by adding an entry to {@link #preserveLatest} with the same syntax as above. An entry usualy contains a wildcard at least for the version. The latest version of all correspondig versions is preserved, all
 * others are designated for deletion. This is applied individually to all artifacts that correspond to <code>&lt;artifact&gt;</code> and <code>&lt;version&gt;</code> if applied.<br/>
 * Examples
 * <ul>
 * <li><code>*-SNAPSHOT</code> (preserve the latest snapshot version of every artifact)</li>
 * <li><code>com.example:*:2.*</code> (preserve all latest versions 2.x of artifacts in group "com.example" (not in subgroup))</li>
 * </ul>
 * <h4>Precendence</h4>
 * <ol>
 * <li>Whitelist. An artifact's version corresponding to any whitelist entry won't be removed.</li>
 * <li>Preserve latest version list. The lastest version corresponding to any of its entries won't be removed.</li>
 * <li>Blacklist. A version corresponding to any blacklist entry will be designated for deletion if it is not matched by an entry in a list above.</li>
 * <li>Preserve latest. A version will be designated for deletion if it is not the latest version of its artifact and not matched by an entry in a list above.</li>
 * </ol>
 * */
@Mojo(name = "clean-repository", defaultPhase = LifecyclePhase.CLEAN)
public class RepositoryCleanerMavenPlugin extends AbstractMojo {

	/** Execution probability can prevent the whole local maven repository from being cleaned each maven run.<br/>
	 * A repository clean will (on average!) be executed only every 1/{@link #executionProbability}<sup>th</sup> run. 
	 */
	@Parameter(defaultValue = "0.01")
	private float executionProbability;
	
	/** Delete builds designated for deletion. */
	@Parameter(defaultValue = "false")
	private boolean deleteBuilds;
	
	/** Delete versions designated for deletion. */
	@Parameter(defaultValue = "false")
	private boolean deleteVersions;

	/** Never delete these items.<br/>
	 * Entry ::= <code> [[&lt;group&gt;:]&lt;artifact&gt;:]&lt;version&gt;</code><br/>
	 * Placeholders ? and * are possible.
	 */
	@Parameter
	private List<String> whitelist;

	/** Preserve latest versions.<br/>
	 * Entry ::= <code> [[&lt;group&gt;:]&lt;artifact&gt;:]&lt;version&gt;</code><br/>
	 * Placeholders ? and * are possible.
	 */
	@Parameter
	private List<String> preserveLatest;

	/** Always designate these items for deletion.
	 * Entry ::= <code> [[&lt;group&gt;:]&lt;artifact&gt;:]&lt;version&gt;</code><br/>
	 * Placeholders ? and * are possible.
	 */
	@Parameter
	private List<String> blacklist;

	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	private MavenSession mavenSession;
	
	
	public void execute() throws MojoExecutionException {
		Log log = getLog();
		Random random = new Random();
		if (executionProbability > random.nextFloat()) {
			List<Filter> whitelist = new LinkedList<>();
			if (this.whitelist != null) {
				for (String s: this.whitelist) {
					whitelist.add(new Filter(s));
				}
			}
			List<Filter> preserveLatest = new LinkedList<>();
			if (this.preserveLatest != null) {
				for (String s: this.preserveLatest) {
					preserveLatest.add(new Filter(s));
				}
			}
			List<Filter> blacklist = new LinkedList<>();
			if (this.blacklist != null) {
				for (String s: this.blacklist)
					blacklist.add(new Filter(s));
			}
			File basedir = new File(mavenSession.getLocalRepository().getBasedir());
			TraverseResult traverseResult = traverse(basedir, basedir, deleteBuilds, deleteVersions, whitelist, preserveLatest, blacklist, log);
			log.info("Repository before:  " + traverseResult.getAllFiles() + " files, " + toHumanReadableBinaryPrefix(traverseResult.getAllSize()) + "B");
			log.info("Removed builds:     " + traverseResult.getDeletedBuilds() + " (" + traverseResult.getDeleteBuildsFiles() + " files, " + toHumanReadableBinaryPrefix(traverseResult.getDeleteBuildsSize()) + "B)");
			log.info("Removed versions:   " + traverseResult.getDeleteVersions() + " (" + traverseResult.getDeleteVersionsFiles() + " files, " + toHumanReadableBinaryPrefix(traverseResult.getDeleteVersionsSize()) + "B)");
			int filesNew = traverseResult.getAllFiles() - traverseResult.deletedBuildsFiles - traverseResult.deletedVersions;
			long sizeNew = traverseResult.getAllSize() - traverseResult.deletedBuildsSize - traverseResult.getDeleteVersionsSize();
			log.info("Repository now:     " + filesNew + " files, " + toHumanReadableBinaryPrefix(sizeNew) + "B");
			log.info("Removable builds:   " + traverseResult.getPotentialBuilds() + " ("+ traverseResult.getPotentialBuildsFiles() + " files, " + toHumanReadableBinaryPrefix(traverseResult.getPotentialBuildsSize()) + "B)");
			log.info("Removable versions: " + traverseResult.getPotentialVersions() + " (" + traverseResult.getPotentialVersionsFiles() + " files, " + toHumanReadableBinaryPrefix(traverseResult.getPotentialVersionsSize()) + "B)");
		}
		else {
			log.info("Skipped due to execution probability");
		}
	}
	

	private class Filter {
		
		private Pattern group;
		private Pattern artifact;
		private Pattern version;
		
		Filter(String string) {
			String [] strings = string.split(":");
			if (string.length() < 1 && strings.length > 2) {
				throw new IllegalArgumentException("Filter syntax [[<group>:]<artifact>:]<version> not matched by \"" + string + "\"");
			}
			Pattern pattern0 = Pattern.compile(strings[0].replace(".", "\\.").replace('?', '.').replace("*", ".*"));
			if (strings.length == 1) {
				version = pattern0;
			}
			else {
				Pattern pattern1 = Pattern.compile(strings[1].replace(".", "\\.").replace('?', '.').replace("*", ".*"));
				if (string.length() == 2) {
					artifact = pattern0;
					version = pattern1;
				}
				else {
					group = pattern0;
					artifact = pattern1;
					version = Pattern.compile(strings[2].replace(".", "\\.").replace('?', '.').replace("*", ".*"));
				}
			}
		}
		
		boolean matches(String group, String artifact, String version) {
			boolean result = this.version.matcher(version).matches();
			if (this.artifact != null) {
				result &= this.artifact.matcher(artifact).matches();
				if (this.group != null) {
					result &= this.group.matcher(group).matches();
				}
			}
			return result;
		}
		
	}
	
	
	private static class TraverseResult {
		
		private boolean pomPresent;
		private int allFiles;
		private long allSize;
		
		private int potentialBuilds;
		private int potentialBuildsFiles;
		private long potentialBuildsSize;
		
		private int potentialVersions;
		private int potentialVersionsFiles;
		private long potentialVersionsSize;
		
		private int deletedBuilds;
		private int deletedBuildsFiles;
		private long deletedBuildsSize;
		
		private int deletedVersions;
		private int deletedVersionsFiles;
		private long deletedVersionsSize;

		public TraverseResult(boolean pomPresent, int allFiles, long allSize, int potentialBuilds, int potentialBuildsFiles, long potentialBuildsSize, int potentialVersions, int potentialVersionsFiles, long potentialVersionsSize, int deletedBuilds, int deletedBuildsFiles, long deletedBuildsSize, int deletedVersions, int deletedVersionsFiles, long deletedVersionSize) {
			this.pomPresent = pomPresent;
			this.allFiles = allFiles;
			this.allSize = allSize;
			this.potentialBuilds = potentialBuilds;
			this.potentialBuildsFiles = potentialBuildsFiles;
			this.potentialBuildsSize = potentialBuildsSize;
			this.potentialVersions = potentialVersions;
			this.potentialVersionsFiles = potentialVersionsFiles;
			this.potentialVersionsSize = potentialVersionsSize;
			this.deletedBuilds = deletedBuilds;
			this.deletedBuildsFiles = deletedBuildsFiles;
			this.deletedBuildsSize = deletedBuildsSize;
			this.deletedVersions = deletedVersions;
			this.deletedVersionsFiles = deletedVersionsFiles;
			this.deletedVersionsSize = deletedVersionSize;
		}

		private boolean isPomPresent() {
			return pomPresent;
		}

		private int getAllFiles() {
			return allFiles;
		}

		private long getAllSize() {
			return allSize;
		}

		private int getPotentialBuilds() {
			return potentialBuilds;
		}

		private int getPotentialBuildsFiles() {
			return potentialBuildsFiles;
		}

		private long getPotentialBuildsSize() {
			return potentialBuildsSize;
		}

		private int getPotentialVersions() {
			return potentialVersions;
		}
		
		private int getPotentialVersionsFiles() {
			return potentialVersionsFiles;
		}

		private long getPotentialVersionsSize() {
			return potentialVersionsSize;
		}

		private int getDeletedBuilds() {
			return deletedBuilds;
		}

		private int getDeleteBuildsFiles() {
			return deletedBuildsFiles;
		}

		private long getDeleteBuildsSize() {
			return deletedBuildsSize;
		}

		private int getDeleteVersions() {
			return deletedVersions;
		}
		
		private int getDeleteVersionsFiles() {
			return deletedVersionsFiles;
		}

		private long getDeleteVersionsSize() {
			return deletedVersionsSize;
		}
	}
	
	private TraverseResult traverse(File basedir, File dir, boolean deleteBuilds, boolean deleteVersions, List<Filter> whitelist, List<Filter> preserveLatest, List<Filter> blacklist, Log log) {
		String thisName = dir.getName(); //version in case of versionDir
		String parentName = dir.getParentFile().getName(); //artifact in case of versionDir
		String parentNameHyphenThisName = parentName + "-" + thisName;
		
		boolean pomPresent = false; //this directory contains builds of an artifact's version
		int allFiles = 0;
		long allSize = 0;
		
		int potentialBuilds = 0;
		int potentialBuildsFiles = 0;
		long potentialBuildsSize = 0;
		
		int potentialVersions = 0;
		int potentialVersionsFiles = 0;
		long potentialVersionsSize = 0;
		
		int deletedBuilds = 0;
		int deletedBuildsFiles = 0;
		long deletedBuildsSize = 0;
		
		int deletedVersions = 0;
		int deletedVersionsFiles = 0;
		long deletedVersionsSize = 0;

		File[] files = dir.listFiles();
		//first file iteration: handle subdirectories, check containing pom for this directory
		boolean pomPresentInSubdir = false;
		for (File file: files) {
			if (file.isDirectory()) {
				TraverseResult traverseResult = traverse(basedir, file, deleteBuilds, deleteVersions, whitelist, preserveLatest, blacklist, log);
				if (traverseResult.isPomPresent()) {
					pomPresentInSubdir = true;
				}
				allFiles += traverseResult.getAllFiles();
				allSize += traverseResult.getAllSize();
				
				potentialBuilds += traverseResult.getPotentialBuilds();
				potentialBuildsFiles += traverseResult.getPotentialBuildsFiles();
				potentialBuildsSize += traverseResult.getPotentialBuildsSize();
				
				potentialVersions += traverseResult.getPotentialVersions();
				potentialVersionsFiles += traverseResult.getPotentialVersionsFiles();
				potentialVersionsSize += traverseResult.getPotentialVersionsSize();
				
				deletedBuilds += traverseResult.getDeletedBuilds();
				deletedBuildsFiles += traverseResult.getDeleteBuildsFiles();
				deletedBuildsSize += traverseResult.getDeleteBuildsSize();
				
				deletedVersions += traverseResult.getDeleteVersions();
				deletedVersionsFiles += traverseResult.getDeleteVersionsFiles();
				deletedVersionsSize += traverseResult.getDeleteVersionsSize();
			}
			if (file.isFile()) {
				allFiles++;
				allSize += file.length();
				if (!pomPresent) {
					if (file.getName().endsWith(".pom")) {
						pomPresent = true;
					}
				}
			}
		}
		//second file iteration: handle builds if pom is present
		if (pomPresent) {
			LinkedHashMap<String, LinkedHashSet<File>> builds = new LinkedHashMap<String, LinkedHashSet<File>>();
			for (File file: files) {
				if (file.isFile()) {
					String name = file.getName();
					if (name.startsWith(parentName) && !name.startsWith(parentNameHyphenThisName)) {
						//TODO Better recognition of build name
						int index = name.lastIndexOf('.');
						String buildName = index < 0 ? name : name.substring(0, index);
						String found = null;
						String newName = buildName;
						for (String collectedBuild: builds.keySet()) {
							if (buildName.startsWith(collectedBuild)) {
								found = collectedBuild;
								newName = collectedBuild;
							}
							else if (collectedBuild.startsWith(buildName)) {
								found = collectedBuild;
								newName = buildName;
							}
						}
						if (found == null) {
							LinkedHashSet<File> linkedHashSet = new LinkedHashSet<File>();
							linkedHashSet.add(file);
							builds.put(newName, linkedHashSet);
						}
						else {
							LinkedHashSet<File> linkedHashSet;
							if (found.equals(newName)) {
								linkedHashSet = builds.get(found);
								linkedHashSet.add(file);
							}
							else {
								linkedHashSet = builds.remove(found);
								linkedHashSet.add(file);
								builds.put(newName, linkedHashSet);
							}
						}
					}
				}
			}
			for (Map.Entry<String, LinkedHashSet<File>> mapEntry: builds.entrySet()) {
				if (log.isDebugEnabled()) {
					String shortdir = dir.getAbsolutePath().substring(basedir.getAbsolutePath().length());
					log.debug("Remove build:    " + shortdir + File.separator + mapEntry.getKey());
				}
				if (deleteBuilds) {
					deletedBuilds++;
				}
				else {
					potentialBuilds++;
				}
				for (File file: mapEntry.getValue()) {
					long size = file.length();
					if (deleteBuilds) {
						if (file.delete()) {
							deletedBuildsFiles++;
							deletedBuildsSize += size;
						}
						else {
							log.warn("Couldn't remove file: " + file.getAbsolutePath());
							potentialBuildsFiles++;
							potentialBuildsSize += size;
						}
					}
					else {
						potentialBuildsFiles++;
						potentialBuildsSize += size;
					}
				}
			}
		}
		//handle versions
		if (pomPresentInSubdir) {
			Arrays.sort(files, Collections.reverseOrder(new Comparator<File>() {
				public int compare(File o1, File o2) {
					return new ComparableVersion(o1.getName()).compareTo(new ComparableVersion(o2.getName()));
				}
			}));
			Set<File> whitelistVersionDirs = new HashSet<>();
			LinkedHashMap<File, List<Filter>> preserveFiltersPerVersionDir = new LinkedHashMap<>(); //version matches these preserveLatest filters
			LinkedHashMap<Filter, List<File>> versionDirsPerFilter = new LinkedHashMap<>(); //preserveLastet filter matches these versions; explicitly LinkedHashMap to preserve order
			Set<File> blacklistVersionDirs = new HashSet<>();
			for (File file: files) {
				if (file.isDirectory()) {
					String name = file.getAbsolutePath().substring(basedir.getAbsolutePath().length() + 1);
					int index = name.lastIndexOf(File.separatorChar);
					String version = name.substring(index + 1);
					String artifact = name.substring(0, index);
					index = artifact.lastIndexOf(File.separatorChar);
					String group = artifact.substring(0, index).replace(File.separatorChar, '.');
					artifact = artifact.substring(index + 1);

					for (Filter filter: whitelist) {
						if (filter.matches(group, artifact, version)) {
							whitelistVersionDirs.add(file);
							break;
						}
					}

					List<Filter> preserveFilters = new LinkedList<>();
					for (Filter filter: preserveLatest) {
						if (filter.matches(group, artifact, version)) {
							preserveFilters.add(filter);
							List<File> versionDirs = versionDirsPerFilter.get(filter);
							if (versionDirs == null) {
								versionDirs = new LinkedList<>();
								versionDirsPerFilter.put(filter, versionDirs);
							}
							versionDirs.add(file);
						}
					}
					preserveFiltersPerVersionDir.put(file, preserveFilters);

					for (Filter filter: blacklist) {
						if (filter.matches(group, artifact, version)) {
							blacklistVersionDirs.add(file);
							break;
						}
					}
				}
			}

			boolean first = true;
			for (File file: files) {
				if (file.isDirectory()) {
					String shortfilename = null;
					if (log.isDebugEnabled()) {
						shortfilename = file.getAbsolutePath().substring(basedir.getAbsolutePath().length());
					}
					if (whitelistVersionDirs.contains(file)) {
						if (log.isDebugEnabled()) {
							
						}
						log.debug("Whitelist:       " + shortfilename);
					}			
					else {
						boolean preserveLastesVersion = false;
						List<Filter> preserveLatestVersionFilters = preserveFiltersPerVersionDir.get(file);
						for (Filter filter: preserveLatestVersionFilters) {
							List<File> versionDirs = versionDirsPerFilter.get(filter);
							if (versionDirs.indexOf(file) == 0) {
								preserveLastesVersion = true;
								log.debug("Preserve latest: " + shortfilename);
							}
						}
						if (!preserveLastesVersion) {
							boolean remove = false;
							if (!blacklistVersionDirs.contains(file)) {
								if (first) {
									log.debug("Latest version:  " + shortfilename);
								}
								else {
									remove = true;
									log.debug("Remove version:  " + shortfilename);
								}
							}
							else {
								log.debug("Blacklist:       " + shortfilename);
								remove = true;
							}
							if (remove) {
								boolean empty = true;
								for (File removeFile: file.listFiles()) {
									long size = removeFile.length();
									if (deleteVersions) {
										if (removeFile.delete()) {
											deletedVersionsFiles++;
											deletedVersionsSize += size;
										}
										else {
											log.warn("Couldn't remove file: " + removeFile.getAbsolutePath());
											empty = false;
											potentialVersionsFiles++;
											potentialVersionsSize += size;
										}
									}
									else {
										empty = false;
										potentialVersionsFiles++;
										potentialVersionsSize += size;
									}
								}
								if (deleteVersions) {
									if (empty && file.delete()) {
										deletedVersions++;
									}
									else {
										log.warn("Couldn't remove directory: " + file.getAbsolutePath());
										potentialVersions++;
									}
								}
								else {
									potentialVersions++;
								}
							}
						}
					}
				}
				first = false;
			}
		}
		return new TraverseResult(pomPresent, allFiles, allSize, potentialBuilds, potentialBuildsFiles, potentialBuildsSize, potentialVersions, potentialVersionsFiles, potentialVersionsSize, deletedBuilds, deletedBuildsFiles, deletedBuildsSize, deletedVersions, deletedVersionsFiles, deletedVersionsSize);
	}
	

	private static final String[] BINARY_PREFIXES = {" ", " ki", " Mi", " Gi", " Ti"};
	
	private static String toHumanReadableBinaryPrefix(long i) {
		DecimalFormat decimalFormat = (DecimalFormat)NumberFormat.getInstance();
		decimalFormat.applyPattern("0.00");
		int floor_log2 = 63 - (int)Long.numberOfLeadingZeros(i);
		int index = floor_log2 / 10;
		if (index >= BINARY_PREFIXES.length) {
			index = BINARY_PREFIXES.length - 1;
		}
		float j = i / (float)(1 << (index * 10));
		return decimalFormat.format(j) + BINARY_PREFIXES[index];
	}
	
}
