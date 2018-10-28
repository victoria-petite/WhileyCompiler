// Copyright 2011 The Whiley Project Developers
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package wyil.transform;

import wybs.lang.Build;
import wybs.lang.CompilationUnit;
import wybs.lang.SyntacticHeap.Allocator;
import wybs.lang.SyntacticItem;
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Name;
import wybs.util.AbstractCompilationUnit.Ref;
import wybs.util.AbstractCompilationUnit.Value;
import wybs.util.AbstractSyntacticHeap;
import wybs.lang.SyntaxError;

import wyc.util.ErrorMessages;
import wycc.cfg.Configuration;
import wycc.util.ArrayUtils;
import wyfs.lang.Content;
import wyfs.lang.Path;
import wyfs.util.Trie;

import static wyc.util.ErrorMessages.*;
import wyil.lang.WyilFile;
import static wyil.lang.WyilFile.*;

import static wyil.lang.WyilFile.Tuple;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import wyil.lang.WyilFile.Decl;
import wyil.lang.WyilFile.Expr;
import wyil.lang.WyilFile.Type;
import wyil.util.AbstractConsumer;

/**
 * Responsible for resolving a name which occurs at some position in a
 * compilation unit. This takes into account the context and, if necessary, will
 * traverse important statements to resolve the query. For example, consider a
 * compilation unit entitled "file":
 *
 * <pre>
 * import std::ascii::*
 *
 * function f(T1 x, T2 y) -> (int r):
 *    return g(x,y)
 * </pre>
 *
 * Here the name "<code>g</code>" is not fully qualified. Depending on which
 * file the matching declaration of <code>g</code> occurs will depend on what
 * its fully qualified name is. For example, if <code>g</code> is declared in
 * the current compilation unit then it's fully quaified name would be
 * <code>test.g</code>. However, it could well be declared in a compilation unit
 * matching the import <code>whiley.lang.*</code>.
 *
 * @author David J. Pearce
 *
 */
public class NameResolution {
	/**
	 * The master list of names and their corresponding records. For each name, this
	 * records whether we have a local declaration, a non-local declaration (which
	 * may or may not have been imported), etc.
	 */
	private final HashMap<QualifiedName, SymbolTableEntry> symbols;

	private final Build.Project project;

	public NameResolution(Build.Task builder) {
		this.symbols = new HashMap<>();
		project = builder.project();
	}

	public void apply(WyilFile module) {
		try {
			// Import local names
			importLocalNames(module.getModule());
			// Import non-local names
			for (WyilFile external : getExternals()) {
				importExternalNames(external.getModule());
			}
			// Create initial set of patches.
			List<Patch> patches = new Resolver().apply(module);
			// Now continue importing until patches all resolved.
			for (int i = 0; i != patches.size(); ++i) {
				resolve(patches.get(i));
			}
		} catch (IOException e) {
			// FIXME: need better error handling within pipeline stages.
			throw new RuntimeException(e);
		}

	}

	/**
	 * Responsible for identifying unresolved names which remain to be resolved to
	 * their corresponding declarations. This is achieved by traversing from a given
	 * declaration and identifying all static variables and callables which are
	 * referred to. In each case, a "patch" is created which identifies a location
	 * within the module which needs to be resolved. Whilst in some cases we could
	 * resolve immediately, for external symbols we cannot. Therefore, patches can
	 * be thought of as "lazy" resolution which works for both local and non-local
	 * names.
	 *
	 * @author David J. Pearce
	 *
	 */
	private class Resolver extends AbstractConsumer<List<Decl.Import>> {
		private ArrayList<Patch> patches = new ArrayList<>();

		public List<Patch> apply(WyilFile module) {
			super.visitModule(module, null);
			return patches;
		}

		@Override
		public void visitUnit(Decl.Unit unit, List<Decl.Import> unused) {
			// Create an initially empty list of import statements.
			super.visitUnit(unit, new ArrayList<>());
		}

		@Override
		public void visitImport(Decl.Import decl, List<Decl.Import> imports) {
			super.visitImport(decl, imports);
			// Add this import statements to list of visible imports
			imports.add(decl);
		}

		@Override
		public void visitLambdaAccess(Expr.LambdaAccess expr, List<Decl.Import> imports) {
			super.visitLambdaAccess(expr, imports);
			// Resolve to qualified name
			QualifiedName name = resolveAs(expr.getName(), imports);
			// Create patch
			patches.add(new Patch(name, expr));
		}

		@Override
		public void visitStaticVariableAccess(Expr.StaticVariableAccess expr, List<Decl.Import> imports) {
			super.visitStaticVariableAccess(expr, imports);
			// Resolve to qualified name
			QualifiedName name = resolveAs(expr.getName(), imports);
			// Create patch
			patches.add(new Patch(name, expr));
		}

		@Override
		public void visitInvoke(Expr.Invoke expr, List<Decl.Import> imports) {
			super.visitInvoke(expr, imports);
			// Resolve to qualified name
			QualifiedName name = resolveAs(expr.getName(), imports);
			// Create patch
			patches.add(new Patch(name, expr));
		}

		@Override
		public void visitTypeNominal(Type.Nominal type, List<Decl.Import> imports) {
			super.visitTypeNominal(type, imports);
			// Resolve to qualified name
			QualifiedName name = resolveAs(type.getName(), imports);
			// Create patch
			patches.add(new Patch(name, type));
		}
	}

	/**
	 * Resolve a given name in a given compilation Unit to all corresponding
	 * (callable) declarations. If the name is already fully qualified then this
	 * amounts to checking that the name exists and finding its declaration(s);
	 * otherwise, we have to process the list of important statements for this
	 * compilation unit in an effort to qualify the name.
	 *
	 * @param name
	 *            The name to be resolved
	 * @param enclosing
	 *            The enclosing declaration in which this name is contained.
	 * @return
	 */
	private QualifiedName resolveAs(Name name, List<Decl.Import> imports) {
		// Resolve unqualified name to qualified name
		switch (name.size()) {
		case 1:
			return unqualifiedResolveAs(name.get(0), imports);
		case 2:
			return partialResolveAs(name.get(0), name.get(1), imports);
		default:
			return new QualifiedName(name.getPath(), name.getLast());
		}
	}

	/**
	 * Resolve a name which is completely unqualified (e.g. <code>to_string</code>).
	 * That is, it's just an identifier. This could be a name in the current unit,
	 * or an explicitly imported name/
	 *
	 * @param name
	 * @param imports
	 * @return
	 */
	private QualifiedName unqualifiedResolveAs(Identifier name, List<Decl.Import> imports) {
		// Attempt to local resolve
		Decl.Unit unit = name.getAncestor(Decl.Unit.class);
		QualifiedName localName = new QualifiedName(unit.getName(), name);
		if (symbols.containsKey(localName)) {
			// Yes, matching local name
			return localName;
		} else {
			// No, attempt to non-local resolve
			for (int i = imports.size() - 1; i >= 0; ++i) {
				Decl.Import imp = imports.get(i);
				if (imp.hasFrom()) {
					// Resolving unqualified names requires "import from".
					Identifier from = imp.getFrom();
					if (from.get().equals("*") || name.equals(from)) {
						return new QualifiedName(imp.getPath(), name);
					}
				}
			}
			// No dice.
			return syntaxError(errorMessage(ErrorMessages.RESOLUTION_ERROR, name.toString()), name);
		}
	}

	/**
	 * Resolve a name which is partially qualified (e.g.
	 * <code>ascii::to_string</code>). This consists of an unqualified unit and a
	 * name.
	 *
	 * @param unit
	 * @param name
	 * @param kind
	 * @param imports
	 * @return
	 */
	private QualifiedName partialResolveAs(Identifier unit, Identifier name, List<Decl.Import> imports) {
		Decl.Unit enclosing = name.getAncestor(Decl.Unit.class);
		if (unit.equals(enclosing.getName().getLast())) {
			// A local lookup on the enclosing compilation unit.
			return unqualifiedResolveAs(name, imports);
		} else {
			for (int i = imports.size() - 1; i >= 0; --i) {
				Decl.Import imp = imports.get(i);
				Tuple<Identifier> path = imp.getPath();
				Identifier last = path.get(path.size() - 1);
				//
				if (!imp.hasFrom() && last.equals(unit)) {
					// Resolving partially qualified names requires no "from".
					QualifiedName qualified = new QualifiedName(path, name);
					if (symbols.containsKey(qualified)) {
						return qualified;
					}
				}
			}
			// No dice.
			return syntaxError(errorMessage(ErrorMessages.RESOLUTION_ERROR, name.toString()), name);
		}
	}

	/**
	 * Resolve a given patch by finding (and potentially importing) the appropriate
	 * declaration and then assigning this to the target expression.
	 *
	 * @param p
	 */
	private void resolve(Patch p) {
		// Import external declarations as necessary
		SymbolTableEntry symbol = symbols.get(p.name);
		if(symbol.external) {
			System.out.println("NEED TO RESOLVE EXTERNAL SYMBOL");
		}
		// Apply the patch
		p.apply(symbols);
	}

	/**
	 * Import all names defined in the local module into the global namespace
	 */
	private void importLocalNames(Decl.Module module) {
		// FIXME: this method is completely broken for so many reasons.
		for (Decl.Unit unit : module.getUnits()) {
			for (Decl d : unit.getDeclarations()) {
				if (d instanceof Decl.Named) {
					Decl.Named n = (Decl.Named) d;
					register(n, false);
				}
			}
		}
	}

	/**
	 * Import all names defined in an external module into the global namespace
	 */
	private void importExternalNames(Decl.Module module) {
		for (Decl.Unit unit : module.getUnits()) {
			for (Decl d : unit.getDeclarations()) {
				if (d instanceof Decl.Named) {
					Decl.Named n = (Decl.Named) d;
					register(n, true);
				}
			}
		}
	}

	/**
	 * Register a new declaration with a given name.
	 *
	 * @param name
	 * @param declaration
	 */
	private void register(Decl.Named declaration, boolean external) {
		QualifiedName name = declaration.getQualifiedName();
		SymbolTableEntry r = symbols.get(name);
		if (r == null) {
			r = new SymbolTableEntry(external);
			symbols.put(name, r);
		}
		// Sanity check whether overloading is valid
		checkValidOverloading(r.declarations, declaration);
		// Add the declaration
		r.declarations.add(declaration);
	}

	/**
	 * Sanity check that there overloading is used correctly. More specifically, we
	 * cannot overload on types or static variables. Furthermore, overloading of
	 * methods or functions is permitted in some situations (i.e. when signatures
	 * vary).
	 *
	 * @param declarations
	 * @param kind
	 */
	private void checkValidOverloading(List<Decl.Named> declarations, Decl.Named declaration) {
		if (declaration instanceof Decl.Type && contains(declarations, Decl.Type.class)) {
			syntaxError("duplicate type declaration", declaration.getName());
		} else if (declaration instanceof Decl.StaticVariable && contains(declarations, Decl.StaticVariable.class)) {
			syntaxError("duplicate type declaration", declaration.getName());
		}
	}

	/**
	 * Read in all external packages so they can be used for name resolution. This
	 * amounts to loading in every WyilFile contained within an external package
	 * dependency.
	 */
	private List<WyilFile> getExternals() throws IOException {
		ArrayList<WyilFile> externals = new ArrayList<>();
		List<Build.Package> pkgs = project.getPackages();
		// Consider each package in turn and identify all contained WyilFiles
		for (int i = 0; i != pkgs.size(); ++i) {
			Build.Package p = pkgs.get(i);
			// FIXME: This is kind broken me thinks. Potentially, we should be able to
			// figure out what modules are supplied via the configuration.
			List<Path.Entry<WyilFile>> entries = p.getRoot().get(Content.filter("**/*", WyilFile.ContentType));
			for (int j = 0; j != entries.size(); ++j) {
				externals.add(entries.get(j).read());
			}
		}
		return externals;
	}

	private <T extends Decl> boolean contains(List<Decl.Named> declarations, Class<T> kind) {
		for (int i = 0; i != declarations.size(); ++i) {
			if (kind.isInstance(declarations.get(i))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Records information about a name which needs to be "patched" with its
	 * corresponding declaration in a given expression.
	 *
	 * @author David J. Pearce
	 *
	 */
	private static class Patch {
		public final QualifiedName name;
		private final SyntacticItem target;

		public Patch(QualifiedName name, SyntacticItem target) {
			this.name = name;
			this.target = target;
		}

		public void apply(Map<QualifiedName, SymbolTableEntry> symbols) {
			// Apply patch to target expression
			switch (target.getOpcode()) {
			case EXPR_staticvariable: {
				Expr.StaticVariableAccess e = (Expr.StaticVariableAccess) target;
				e.setDeclaration(select(name, Decl.StaticVariable.class, symbols));
				break;
			}
			case EXPR_invoke: {
				Expr.Invoke e = (Expr.Invoke) target;
				Decl.Callable[] resolved = selectAll(name, Decl.Callable.class, symbols);
				e.setDeclarations(filterParameters(e.getOperands().size(), resolved));
				break;
			}
			case EXPR_lambdaaccess: {
				Expr.LambdaAccess e = (Expr.LambdaAccess) target;
				Decl.Callable[] resolved = selectAll(name, Decl.Callable.class, symbols);
				e.setDeclarations(filterParameters(e.getParameterTypes().size(), resolved));
				break;
			}
			default:
			case TYPE_nominal: {
				Type.Nominal e = (Type.Nominal) target;
				e.setDeclaration(select(name, Decl.Type.class, symbols));
				break;
			}
			}
		}

		/**
		 * Resolve a name which is fully qualified (e.g.
		 * <code>std::ascii::to_string</code>) to first matching declaration. This
		 * consists of a qualified unit and a name.
		 *
		 * @param name
		 *            Fully qualified name
		 * @param kind
		 *            Declaration kind we are resolving.
		 * @return
		 */
		private <T extends Decl> T select(QualifiedName name, Class<T> kind,
				Map<QualifiedName, SymbolTableEntry> symbols) {
			SymbolTableEntry r = symbols.get(name);
			Identifier id = name.getName();
			for (int i = 0; i != r.declarations.size(); ++i) {
				Decl.Named d = r.declarations.get(i);
				if (kind.isInstance(d)) {
					return (T) d;
				}
			}
			return syntaxError(errorMessage(ErrorMessages.RESOLUTION_ERROR, id.toString()), id);
		}

		/**
		 * Resolve a name which is fully qualified (e.g.
		 * <code>std::ascii::to_string</code>) to all matching declarations. This
		 * consists of a qualified unit and a name.
		 *
		 * @param name
		 *            Fully qualified name
		 * @param kind
		 *            Declaration kind we are resolving.
		 * @return
		 */
		private <T extends Decl> T[] selectAll(QualifiedName name, Class<T> kind,
				Map<QualifiedName, SymbolTableEntry> symbols) {
			SymbolTableEntry r = symbols.get(name);
			Identifier id = name.getName();
			// Determine how many matches
			int count = 0;
			for (int i = 0; i != r.declarations.size(); ++i) {
				Decl.Named d = r.declarations.get(i);
				if (kind.isInstance(d)) {
					count++;
				}
			}
			// Create the array
			@SuppressWarnings("unchecked")
			T[] matches = (T[]) Array.newInstance(kind, count);
			// Populate the array
			for (int i = 0, j = 0; i != r.declarations.size(); ++i) {
				Decl.Named d = r.declarations.get(i);
				if (kind.isInstance(d)) {
					matches[j++] = (T) d;
				}
			}
			// Check for resolution error
			if (matches.length == 0) {
				return syntaxError(errorMessage(ErrorMessages.RESOLUTION_ERROR, id.toString()), id);
			} else {
				return matches;
			}
		}

		/**
		 * Filter the given callable declarations based on their parameter count.
		 *
		 * @param parameters
		 * @param resolved
		 * @return
		 */
		private Decl.Callable[] filterParameters(int parameters, Decl.Callable[] resolved) {
			// Remove any with incorrect number of parameters
			for (int i = 0; i != resolved.length; ++i) {
				Decl.Callable c = resolved[i];
				if (parameters > 0 && c.getParameters().size() != parameters) {
					resolved[i] = null;
				}
			}
			return ArrayUtils.removeAll(resolved, null);
		}

	}

	/**
	 * Records information associated with a given symbol, such as whether it is
	 * defined within the current module or externally.
	 *
	 * @author David J. Pearce
	 *
	 */
	private static class SymbolTableEntry {
		/**
		 * Identifies the complete set of declarations associated with this symbol.
		 */
		public final ArrayList<Decl.Named> declarations;
		/**
		 * Indicates whether or not this entry is externally defined in a dependency, or
		 * defined within the current module. Observe that externally defined symbols
		 * become internally defined once they are imported.
		 */
		public boolean external;

		public SymbolTableEntry(boolean external) {
			this.external = external;
			this.declarations = new ArrayList<>();
		}
	}

	private static Ref<Decl> REF_UNKNOWN_DECL = new Ref(new Decl.Unknown());

	private static class ImportAllocator extends AbstractSyntacticHeap.Allocator {

		public ImportAllocator(AbstractSyntacticHeap heap) {
			super(heap);
		}

		@Override
		public SyntacticItem allocate(SyntacticItem item) {
			if (item instanceof Ref) {
				Ref ref = (Ref) item;
				SyntacticItem referent = ref.get();
				if (referent.getHeap() != heap && referent instanceof Decl.Named) {
					Decl.Named named = (Decl.Named) referent;
					// FIXME: could avoid allocating multiple unknown references here.
					// FIXME: could potentially allocate referent in some cases.
					return super.allocate(REF_UNKNOWN_DECL);
				}
			}
			return super.allocate(item);
		}
	}

	/**
	 * Throw an syntax error.
	 *
	 * @param msg
	 * @param e
	 * @return
	 */
	private static <T> T syntaxError(String msg, SyntacticItem e) {
		// FIXME: this is a kludge
		CompilationUnit cu = (CompilationUnit) e.getHeap();
		throw new SyntaxError(msg, cu.getEntry(), e);
	}
}