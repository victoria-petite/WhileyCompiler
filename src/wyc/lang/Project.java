package wyc.lang;

import java.io.*;
import java.util.*;

import wyc.builder.Builder;
import wyc.util.path.ContentType;
import wyc.util.path.Path;
import wyil.io.ModuleReader;
import wyil.lang.*;
import wyil.ModuleLoader;
import wyil.util.Logger;
import wyil.util.ResolveError;
import wyil.util.Triple;
import wyjc.io.ClassFileLoader;

/**
 * A Whiley project represents the contextual information underpinning a given
 * compilation. This includes the WHILEYPATH, the package roots for all source
 * files and the binary destination folders as well. Bringing all of this
 * information together helps manage it, and enables a certain amount of global
 * analysis. For example, we can analyse dependencies between source files.
 * 
 * @author David J. Pearce
 */
public final class Project implements Logger,ModuleLoader {	
	
	
	// Hack for now
	public static final ContentType<Module> ModuleContentType = new ContentType<Module>() {

		public boolean matches(String suffix) {
			return suffix.equals("class");
		}
		
		public Module read(Path.Entry entry) throws Exception {
			Runtime runtime = Runtime.getRuntime();
			long time = System.currentTimeMillis();
			long memory = runtime.freeMemory();
			ClassFileLoader loader = new ClassFileLoader();
			ModuleID mid = entry.id();		
			Module mi = loader.read(mid, entry.inputStream());
					
//			if(mi != null) {
//				logger.logTimedMessage("Loaded " + entry.location() + ":" + mid,
//						System.currentTimeMillis() - time, memory - runtime.freeMemory());
//				moduleCache.put(mi.id(), mi);
//			} else {
//				
//				logger.logTimedMessage("Ignored " + entry.location() + ":" + mid,
//						System.currentTimeMillis() - time, memory - runtime.freeMemory());
//			}
//			
			return mi;			
		}
		
		public void write(Path.Entry entry, Module contents) {
			throw new UnsupportedOperationException();
		}
	};
	
	/**
	 * The source roots are locations which may contain the root of a package
	 * structure containing source files.
	 */
	private ArrayList<Path.Root> sourceRoots;
	
	/**
	 * The binary roots represent the root of all binary module files. This
	 * includes external libraries required for compiling this project, as well
	 * as the binary folders correspond to source folders. They may exist in
	 * several forms, including as jar files or directory roots (for binary
	 * files).
	 */
	private ArrayList<Path.Root> binaryRoots;	
	
	/**
	 * The builder used for compiling source files into modules.
	 */
	private Builder builder;
	
	/**
	 * A map from module identifiers to module objects. This is the master cache
	 * of modules which have been loaded or compiled. Once a module has been
	 * entered into the moduleCache, it will not be loaded again.
	 */
	private HashMap<ModuleID, Module> moduleCache = new HashMap<ModuleID, Module>();	
		
	/**
	 * The logger is used to log messages for the project.
	 */
	private Logger logger = Logger.NULL;
	
	public Project(Collection<? extends Path.Root> srcRoots, Collection<? extends Path.Root> libRoots) {
		this.sourceRoots = new ArrayList<Path.Root>(srcRoots);
		this.binaryRoots = new ArrayList<Path.Root>(libRoots);
	}
	
	// ======================================================================
	// Public Configuration Interface
	// ======================================================================		
	
	/**
	 * Set the logger for this module loader.
	 * 
	 * @param logger
	 */
	public void setLogger(Logger logger) {
		this.logger = logger;
	}
	
	/**
	 * Set the builder associated with this project.
	 * 
	 * @param builder
	 */
	public void setBuilder(Builder builder) {
		this.builder = builder;
	}
	
	// FIXME: to be deprecated
	public void update(Module m) {
		moduleCache.put(m.id(), m);
	}
	
	// ======================================================================
	// Public Mutator Interface
	// ======================================================================		

	/**
	 * Build the project using the given project builder.
	 */
	public void build() throws Exception {
		
		// first, determine what has changed.
		ArrayList<Path.Entry<SourceFile>> delta = new ArrayList<Path.Entry<SourceFile>>(); 
		for(Path.Root root : sourceRoots) {
			for(Path.Entry e : root.list()) {
				// FIXME: surely there must be a better way!
				if(e.contentType() == SourceFile.ContentType && e.isModified()) {
					delta.add(e);
				}
			}
		}
		
		builder.build(delta);
	}
	
	/**
	 * Log a message, along with a time. The time is used to indicate how long
	 * it took for the action being reported. This is used primarily to signal
	 * that a given stage has been completed in a certain amount of time.
	 * 
	 * @param msg
	 * @param time --- total time taken for stage
     * @param memory --- difference in available free memory
	 */
	public void logTimedMessage(String msg, long time, long memory) {
		logger.logTimedMessage(msg, time, memory);
	}
	
	// ======================================================================
	// Public Accessor Interface
	// ======================================================================		

	/**
	 * Determine whether a given package actually exists or not.
	 * 
	 * @param pid --- Package ID to check
	 * @return
	 */
	public boolean isPackage(PkgID pid) {
		try {
			for(Path.Root root : sourceRoots) {
				if(root.exists(pid)) {
					return true;
				}
			}
			for(Path.Root root : binaryRoots) {
				if(root.exists(pid)) {
					return true;
				}
			}
		} catch(Exception e) {
			// FIXME: figure how best to propagate this exception
		}
		
		return false;
	}
	
	/**
	 * Determine whether a given module actually exists or not.
	 * 
	 * @param mid --- Module ID to check
	 * @return
	 */
	public boolean isModule(ModuleID mid) {
		try {
			for(Path.Root root : sourceRoots) {
				if(root.exists(mid,SourceFile.ContentType)) {
					return true;
				}
			}
			for(Path.Root root : binaryRoots) {
				if(root.get(mid,ModuleContentType) != null) {
					return true;
				}
			}
		} catch(Exception e) {
			// FIXME: figure how best to propagate this exception
		}
		return false;
	}	
	
	/**
	 * This method attempts to load a whiley module. The module is searched for
	 * on the WHILEYPATH. A resolve error is thrown if the module cannot be
	 * found or otherwise loaded.
	 * 
	 * @param module
	 *            The module to load
	 * @return the loaded module
	 */
	public Module get(ModuleID module) throws ResolveError {		
		Module m = moduleCache.get(module);
						
		if (m != null) {
			return m; // module was previously loaded and cached
		} 
		
		try {
			// ok, now look for module inside package roots.
			Path.Entry<Module> entry = null;			
			for(Path.Root root : binaryRoots) {
				entry = root.get(module,ModuleContentType);
				if(entry != null) {
					break;
				}
			}
			if(entry == null) {
				throw new ResolveError("Unable to find module: " + module);
			}			
			m = entry.read();
			if(m == null) {
				throw new ResolveError("Unable to find module: " + module);
			}
			return m;						
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {				
			throw new ResolveError("Unable to find module: " + module,e);
		}	
	}	
	
	/**
	 * Return the set of all modules in a given package.
	 * 
	 * @param pid
	 *            --- package to list.
	 * @return
	 * @throws Exception
	 */
	public Set<ModuleID> get(PkgID pid) throws Exception {
		HashSet<ModuleID> contents = new HashSet<ModuleID>();		
		
		for(Path.Root root : sourceRoots) {			
			if(root.exists(pid)) {
				for (Path.Entry e : root.list(pid)) {
					contents.add(e.id());
				}
			}
		}
		for(Path.Root root : binaryRoots) {
			if(root.exists(pid)) {
				for (Path.Entry e : root.list(pid)) {
					contents.add(e.id());
				}
			}
		}

		return contents;
	}	
}
