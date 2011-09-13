package wyautl.util;

import java.io.*;
import java.util.*;
import wyil.lang.*;
import wyjvm.io.BinaryOutputStream;
import wyautl.io.*;
import wyautl.lang.*;

/**
 * The generator class is used generate automatas, primarily for testing
 * purposes.
 * 
 * @author djp
 * 
 */
public class Generator {

	public interface Data {
		/**
		 * Generate all possible forms of supplementary data for the given
		 * state.
		 * 
		 * @param state
		 * @return
		 */
		public List<Object> generate(Automata.State state);
	}
	
	public static final class Kind {
		/**
		 * Determine whether this kind is deterministic or not.
		 */
		public boolean DETERMINISTIC;

		/**
		 * Determine minimum number of children this kind can have.
		 */
		public int MIN_CHILDREN;

		/**
		 * Determine maximum number of children this kind can have.
		 */
		public int MAX_CHILDREN;

		/**
		 * A method for generating approprate supplementary data.
		 */
		public Data DATA;
		
		public Kind(boolean deterministic, int min, int max, Data data) {
			this.DETERMINISTIC = deterministic;
			this.MIN_CHILDREN = min;
			this.MAX_CHILDREN = max;
			this.DATA = data;
		}
	}

	public static class Config {
		/**
		 * Provide details of kinds used.
		 */
		public Kind[] KINDS;
		
		/**
		 * Allow recursive links or not.
		 */
		public boolean RECURSIVE;
		
		/**
		 * Determine size of automatas to generate.
		 */
		public int SIZE;
	}
	
	private final static class Template {
		public final int[] kinds;
		public final int[] children;		
		public final BitSet transitions;
		
		public Template(int size) {
			this.kinds = new int[size];
			this.children = new int[size];
			transitions = new BitSet(size*size);
		}
		
		public final void add(int from, int to) {
			transitions.set((from*kinds.length)+to,true);
		}
		
		public final void remove(int from, int to) {
			transitions.set((from*kinds.length)+to,false);
		}
		
		public final boolean isTransition(int from, int to) {
			return transitions.get((from*kinds.length)+to);
		}
	}
	
	/**
	 * Turn a template into an actual automata.
	 * 
	 * @param template
	 * @param writer
	 */
	private static void generate(Template template,
			GenericWriter<Automata> writer, Config config) throws IOException {
		
		Kind[] KINDS = config.KINDS;
		int[] kinds = template.kinds;
		int[] nchildren = template.children;
		Automata.State[] states = new Automata.State[kinds.length];
		
		for(int i=0;i!=kinds.length;++i) {
			int kind = kinds[i];
			int[] children = new int[nchildren[i]];
			int index = 0;
			for(int j=0;j!=kinds.length;++j) {
				if(template.isTransition(i,j)) {
					children[index++] = j;
				}
			}			
			states[i] = new Automata.State(kind,children,KINDS[kind].DETERMINISTIC);
		}
		Automata automata = new Automata(states);					
		
		generate(0,automata,writer,config);				
	}
	
	private static void generate(int index, Automata automata,
			GenericWriter<Automata> writer, Config config) throws IOException {
		if(index == automata.size()) {			
			writer.write(automata);
			writer.flush();
			count++;
			if(verbose) {
				System.err.print("\rWrote " + count + " automatas.");
			}	
		} else {			
			Automata.State state = automata.states[index];
			Kind kind = config.KINDS[state.kind];			
			if(kind.DATA != null) {
				// this kind requires supplementary data
				List<Object> datas = kind.DATA.generate(state);
				for(Object data : datas) {
					state.data = data;
					generate(index+1,automata,writer,config);
				}
			} else {
				generate(index+1,automata,writer,config);
			}
		}
	}
	
	private static boolean verbose = false;
	private static int count = 0;
	
	private static void debug(Template base) {		
		int[] kinds = base.kinds;
		for(int i=0;i!=kinds.length;++i) {
			int kind = kinds[i];
			System.out.print("(" + kind +")");
		}
		for(int i=0;i!=kinds.length;++i) {
			for(int j=0;j!=kinds.length;++j) {
				if(base.isTransition(i,j)) {
					System.out.print(i + "->" +j + " ");
				}
			}						
		}
		System.out.println();
	}
	
	private static void generate(int from, int to, Template base,
			GenericWriter<Automata> writer, Config config) throws IOException {
		int[] nchildren = base.children;
		int[] kinds = base.kinds;
		Kind fromKind = config.KINDS[kinds[from]];		
		
		if(to >= config.SIZE) {	
			if(nchildren[from] < fromKind.MIN_CHILDREN){
				// this indicates an invalid automata, since this state doesn't
				// have enough children.				
				return;
			} 
			
			if(from > 0) {
				// non-root state, so ensure has parent
				boolean hasParent = false;
				for(int i=0;i!=from;++i) {
					if(base.isTransition(i,from)) {
						hasParent = true;
						break;
					}
				}

				if(!hasParent) { return; }
			}
			
			from = from + 1;
			to = from;
			
			if(to >= config.SIZE) {
				// ok, generate the automata.				
				generate(base,writer,config);
				return;
			} 
		}
		
		// first, generate no edge (if allowed)			 
		generate(from,to+1,base,writer,config);
		Kind toKind = config.KINDS[kinds[to]];	
		
		// second, generate forward edge (if allowed)		
		if (from != to && nchildren[from] < fromKind.MAX_CHILDREN) {
			nchildren[from]++;
			base.add(from,to);
			generate(from,to+1,base,writer,config);
			
			// third, generate bidirectional edge (if allowed)
			if (config.RECURSIVE && nchildren[to] < toKind.MAX_CHILDREN) {
				nchildren[to]++;
				base.add(to, from);
				generate(from, to+1, base, writer, config);
				base.remove(to, from);
				nchildren[to]--;		
			}
			base.remove(from,to);
			nchildren[from]--;
		} 
		
		// fourth, generate reverse edge (if allowed)				
		if (config.RECURSIVE && nchildren[to] < toKind.MAX_CHILDREN) {
			nchildren[to]++;
			base.add(to, from);
			generate(from, to+1, base, writer, config);
			base.remove(to, from);
			nchildren[to]--;		
		}
	}
	
	private static void generate(int index, Template base,
			GenericWriter<Automata> writer, Config config) throws IOException {
		if(index == config.SIZE) {
			// now start generating transitions
			generate(0,0,base,writer,config);
		} else {
			Kind[] kinds = config.KINDS;
			for(int k=0;k!=kinds.length;++k) {
				Kind kind = kinds[k];
				if(kind != null) {
					base.kinds[index] = k;								
					generate(index+1,base,writer,config);
				}
			}
		}
	}
	
	/**
	 * The generate method generates all possible automatas matching of a given
	 * size. Observe that this may be an extremely expensive operation, and
	 * significant care must be exercised in setting the configuration
	 * parameters!
	 * 
	 * @param size
	 *            --- generated automatas will have exactly this size.
	 * @param recursive
	 *            --- generated automatas permit recursive links.
	 * @param writer
	 *            --- generate automatas are written to this writer.
	 */
	public static void generate(GenericWriter<Automata> writer, Config config) throws IOException {
		Template base = new Template(config.SIZE);
		generate(0,base,writer,config);
	}
	
	private static final Config config = new Config() {{
		KINDS = new Kind[]{
			new Kind(false,0,2,null),
			new Kind(true,1,1,null)
		};
		RECURSIVE = true;
		SIZE = 3;
	}};
		
	public static void main(String[] args) {		
		boolean binary = false;		
		GenericWriter<Automata> writer;
		OutputStream out = System.out;
		int minSize = 1;
		int maxSize = config.SIZE;
		
		try {
			int index = 0;
			while(index < args.length) {
				if(args[index].equals("-b")) {
					binary=true;
				} else if(args[index].equals("-o")) {
					String filename = args[++index];
					out = new FileOutputStream(filename);
				} else if(args[index].equals("-s") || args[index].equals("-size")) {
					String arg = args[++index];
					if(arg.indexOf(':') >= 0) {
						String[] ss = arg.split(":");
						minSize = Integer.parseInt(ss[0]);
						maxSize = Integer.parseInt(ss[1]);
					} else {
						maxSize = Integer.parseInt(arg);						
					}
				} else if(args[index].equals("-v") || args[index].equals("-verbose")) {
					verbose = true;
				} else if(args[index].equals("-m") || args[index].equals("-model")) {
					config.RECURSIVE = false;
					for(Kind k : config.KINDS) {
						if(!k.DETERMINISTIC) { 
							k.DETERMINISTIC = true;
							k.MIN_CHILDREN = 0;
							k.MAX_CHILDREN = 1;
						}
					}
				}
				index++;
			}

			if(binary) {
				BinaryOutputStream bos = new BinaryOutputStream(out);
				writer = new BinaryAutomataWriter(bos);
			} else {
				writer = new TextAutomataWriter(out);
			}					
			for(int i=minSize;i<=maxSize;++i) {
				config.SIZE = i;
				generate(writer,config);				
			}			
			if(!verbose) {
				System.err.print("\rWrote " + count + " automatas.");
			}
			writer.close();									
		} catch(IOException ex) {
			System.out.println("Exception: " + ex);
		}		
	}
}
