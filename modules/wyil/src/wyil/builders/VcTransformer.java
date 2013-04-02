// Copyright (c) 2012, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyil.builders;

import static wybs.lang.SyntaxError.internalFailure;
import static wybs.lang.SyntaxError.syntaxError;
import static wyil.util.ErrorMessages.errorMessage;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.*;

import wybs.lang.*;
import wybs.util.Pair;
import wybs.util.Trie;
import wyil.lang.*;
import wyil.util.ErrorMessages;

import wycs.core.SemanticType;
import wycs.core.Value;
import wycs.io.WyalFileClassicalPrinter;
import wycs.syntax.*;
import wycs.transforms.ConstraintInline;
import wycs.transforms.VerificationCheck;
import wycs.util.Exprs;

/**
 * Responsible for converting a given Wyil bytecode into an appropriate
 * constraint which encodes its semantics.
 * 
 * @author David J. Pearce
 * 
 */
public class VcTransformer {
	private final Builder builder;
	// private final WyilFile.Case method;
	private final WyalFile wycsFile;
	private final String filename;
	private final boolean assume;

	public VcTransformer(Builder builder, WyalFile wycsFile,
			String filename, boolean assume) {
		this.builder = builder;
		this.filename = filename;
		this.assume = assume;
		this.wycsFile = wycsFile;
	}

	public String filename() {
		return filename;
	}

	public void end(VcBranch.LoopScope scope,
			VcBranch branch) {
		// not sure what really needs to be done here, in fact.
	}

	public void exit(VcBranch.LoopScope scope,
			VcBranch branch) {
		branch.addAll(scope.constraints);
	}

	private static int indexCount = 0;
	
	public void end(VcBranch.ForScope scope, VcBranch branch) {
		// we need to build up a quantified formula here.

		ArrayList<Expr> constraints = new ArrayList<Expr>();
		constraints.addAll(scope.constraints);

		Expr root = Expr.Nary(Expr.Nary.Op.AND, constraints, branch.entry()
				.attributes());

		SyntacticType type = convert(scope.loop.type.element(), branch.entry());

		Pair<SyntacticType, Expr.Variable>[] vars;
		Expr index;
		
		if (scope.loop.type instanceof Type.EffectiveList) {
			// FIXME: hack to work around limitations of whiley for
			// loops.
			Expr.Variable idx = Expr.Variable("i" + indexCount++);
			vars = new Pair[] { 
					new Pair<SyntacticType, Expr.Variable>(new SyntacticType.Primitive(SemanticType.Int),idx),
					new Pair<SyntacticType, Expr.Variable>(type, scope.index) 
			};
			index = Expr.Nary(Expr.Nary.Op.TUPLE, new Expr[] {idx,scope.index});
		} else {
			 vars = new Pair[] { 
					 new Pair<SyntacticType, Expr.Variable>(type, scope.index) 
			};
			 index = scope.index;
		}
		root = Expr.Binary(Expr.Binary.Op.IMPLIES,
				Expr.Binary(Expr.Binary.Op.IN, index, scope.source), root);
		branch.add(Expr.ForAll(vars, root, branch.entry().attributes()));
	}

	public void exit(VcBranch.ForScope scope,
			VcBranch branch) {
		ArrayList<Expr> constraints = new ArrayList<Expr>();
		constraints.addAll(scope.constraints);

		Expr root = Expr.Nary(Expr.Nary.Op.AND, constraints, branch.entry()
				.attributes());
		SyntacticType type = convert(scope.loop.type.element(), branch.entry());
		Pair<SyntacticType, Expr.Variable>[] vars;
		Expr index;
		
		if (scope.loop.type instanceof Type.EffectiveList) {
			// FIXME: hack to work around limitations of whiley for
			// loops.
			Expr.Variable idx = Expr.Variable("i" + indexCount++);
			vars = new Pair[] { 
					new Pair<SyntacticType, Expr.Variable>(new SyntacticType.Primitive(SemanticType.Int),idx),
					new Pair<SyntacticType, Expr.Variable>(type, scope.index) 
			};
			index = Expr.Nary(Expr.Nary.Op.TUPLE, new Expr[] {idx,scope.index});
		} else {
			 vars = new Pair[] { 
					 new Pair<SyntacticType, Expr.Variable>(type, scope.index) 
			};
			 index = scope.index;
		}
		root = Expr.Binary(Expr.Binary.Op.IMPLIES,
				Expr.Binary(Expr.Binary.Op.IN, index, scope.source), root);
		
		branch.add(Expr.Exists(vars, root, branch.entry().attributes()));
	}

	public void exit(VcBranch.TryScope scope,
			VcBranch branch) {

	}

	protected void transform(Code.Assert code, VcBranch branch) {
		Expr test = buildTest(code.op, code.leftOperand, code.rightOperand,
				code.type, branch);

		if (!assume) {
			// We need the entry branch to determine the parameter types.
			Expr assumptions = branch.constraints();
			Expr implication = Expr.Binary(Expr.Binary.Op.IMPLIES, assumptions,
					test);
			Expr assertion = buildAssertion(0, implication, branch);
			wycsFile.add(wycsFile.new Assert(code.msg, assertion, branch
					.entry().attributes()));
		} else {
			branch.add(test);
		}
	}

	/**
	 * Recursively descend the scope stack building up appropriate
	 * parameterisation of the core assertion as we go.
	 * 
	 * @param index
	 *            --- current depth into the scope stack.
	 * @param implication
	 *            --- the core assertion being parameterised.
	 * @param branch
	 *            --- current branch containing scope stack.
	 * @return
	 */
	protected Expr buildAssertion(int index, Expr implication,
			VcBranch branch) {
		if (index < branch.nScopes()) {
			Expr contents = buildAssertion(index + 1, implication, branch);

			VcBranch.Scope scope = branch.scope(index);
			if (scope instanceof VcBranch.EntryScope) {
				VcBranch.EntryScope es = (VcBranch.EntryScope) scope;
				Pair<SyntacticType,Expr.Variable>[] vars = convertParameters(
						es.declaration.type().params(), es.declaration);
				if (vars.length > 0) {
					return Expr.ForAll(vars, contents);
				} else {
					return contents;
				}
			} else if (scope instanceof VcBranch.ForScope) {
				VcBranch.ForScope ls = (VcBranch.ForScope) scope;
				SyntacticType type = convert(ls.loop.type.element(),
						branch.entry());

				Expr idx;
				
				Pair<SyntacticType,Expr.Variable>[] vars;				
				// now, deal with modified operands
				int[] modifiedOperands = ls.loop.modifiedOperands;
				int start;
				if (ls.loop.type instanceof Type.EffectiveList) {
					// FIXME: hack to work around limitations of whiley for
					// loops.
					Expr.Variable i = Expr.Variable("i" + indexCount++);
					vars = new Pair[2 + modifiedOperands.length];
					vars[0] = new Pair<SyntacticType, Expr.Variable>(
							new SyntacticType.Primitive(SemanticType.Int), i);
					vars[1] = new Pair<SyntacticType, Expr.Variable>(type,
							ls.index);
					idx = Expr.Nary(Expr.Nary.Op.TUPLE, new Expr[] { i,
							ls.index });
					start = 2;
				} else {
					 vars = new Pair[1 + modifiedOperands.length];
				     vars[0] = new Pair<SyntacticType,Expr.Variable>(type, ls.index);					 
					idx = ls.index;
					start = 1;
				}
				contents = Expr.Binary(Expr.Binary.Op.IMPLIES,
						Expr.Binary(Expr.Binary.Op.IN, idx, ls.source), contents);

				for (int i = 0; i != modifiedOperands.length; ++i) {
					int reg = modifiedOperands[i];
					// FIXME: should not be INT here.
					SyntacticType t = new SyntacticType.Primitive(SemanticType.Int);
					Expr.Variable v = Expr.Variable("r" + reg);
					vars[i + start] = new Pair<SyntacticType,Expr.Variable>(t,v);
				}

				return Expr.ForAll(vars, contents);
			} else if (scope instanceof VcBranch.LoopScope) {
				VcBranch.LoopScope ls = (VcBranch.LoopScope) scope;
				// now, deal with modified operands
				int[] modifiedOperands = ls.loop.modifiedOperands;
				Pair<SyntacticType,Expr.Variable>[] vars = new Pair[modifiedOperands.length];
				for (int i = 0; i != modifiedOperands.length; ++i) {
					int reg = modifiedOperands[i];
					// FIXME: should not be INT here.
					SyntacticType t = new SyntacticType.Primitive(SemanticType.Int);
					Expr.Variable v = Expr.Variable("r" + reg);
					vars[i] = new Pair<SyntacticType,Expr.Variable>(t,v);
				}
				return Expr.ForAll(vars, contents);
			} else {
				return contents;
			}
		} else {
			return implication;
		}
	}

	protected void transform(Code.Assume code, VcBranch branch) {
		// At this point, what we do is invert the condition being asserted and
		// check that it is unsatisfiable.
		Expr test = buildTest(code.op, code.leftOperand, code.rightOperand,
				code.type, branch);
		branch.add(test);
	}

	protected void transform(Code.Assign code, VcBranch branch) {
		branch.write(code.target, branch.read(code.operand));
	}

	protected void transform(Code.BinArithOp code, VcBranch branch) {
		Expr lhs = branch.read(code.leftOperand);
		Expr rhs = branch.read(code.rightOperand);
		Expr.Binary.Op op;

		switch (code.kind) {
		case ADD:
			op = Expr.Binary.Op.ADD;
			break;
		case SUB:
			op = Expr.Binary.Op.SUB;
			break;
		case MUL:
			op = Expr.Binary.Op.MUL;
			break;
		case DIV:
			op = Expr.Binary.Op.DIV;
			break;
		case RANGE:
			branch.write(code.target,
					Exprs.ListRange(lhs, rhs, branch.entry().attributes()));
			return;
		default:
			internalFailure("unknown binary operator", filename, branch.entry());
			return;
		}

		branch.write(code.target,
				Expr.Binary(op, lhs, rhs, branch.entry().attributes()));
	}

	protected void transform(Code.BinListOp code, VcBranch branch) {
		Expr lhs = branch.read(code.leftOperand);
		Expr rhs = branch.read(code.rightOperand);

		switch (code.kind) {
		case APPEND:
			// do nothing
			break;
		case LEFT_APPEND:
			rhs = Expr.Nary(Expr.Nary.Op.LIST,new Expr[] { rhs }, branch.entry().attributes());
			break;
		case RIGHT_APPEND:
			lhs = Expr.Nary(Expr.Nary.Op.LIST,new Expr[] { lhs }, branch.entry().attributes());
			break;
		default:
			internalFailure("unknown binary operator", filename, branch.entry());
			return;
		}

		branch.write(code.target,
				Expr.Binary(Expr.Binary.Op.LISTAPPEND,lhs, rhs, branch.entry().attributes()));
	}

	protected void transform(Code.BinSetOp code, VcBranch branch) {
		Collection<Attribute> attributes = branch.entry().attributes();
		Expr lhs = branch.read(code.leftOperand);
		Expr rhs = branch.read(code.rightOperand);
		Expr val;

		switch (code.kind) {
		case UNION:
			val = Expr.Binary(Expr.Binary.Op.SETUNION,lhs, rhs, attributes);
			break;
		case LEFT_UNION:
			rhs = Expr.Nary(Expr.Nary.Op.SET, new Expr[] { rhs }, branch
					.entry().attributes());
			val = Expr.Binary(Expr.Binary.Op.SETUNION,lhs, rhs, attributes);
			break;
		case RIGHT_UNION:
			lhs = Expr.Nary(Expr.Nary.Op.SET, new Expr[] { lhs }, branch
					.entry().attributes());
			val = Expr.Binary(Expr.Binary.Op.SETUNION,lhs, rhs, attributes);
			break;
		case INTERSECTION:
			val = Expr.Binary(Expr.Binary.Op.SETINTERSECTION,lhs, rhs, attributes);
			break;
		case LEFT_INTERSECTION:
			rhs = Expr.Nary(Expr.Nary.Op.SET, new Expr[] { rhs }, branch
					.entry().attributes());
			val = Expr.Binary(Expr.Binary.Op.SETINTERSECTION,lhs, rhs, attributes);
			break;
		case RIGHT_INTERSECTION:
			lhs = Expr.Nary(Expr.Nary.Op.SET, new Expr[] { lhs }, branch
					.entry().attributes());
			val = Expr.Binary(Expr.Binary.Op.SETINTERSECTION,lhs, rhs, attributes);
			break;
		case LEFT_DIFFERENCE:
			rhs = Expr.Nary(Expr.Nary.Op.SET, new Expr[] { rhs }, branch
					.entry().attributes());
			val = Expr.Binary(Expr.Binary.Op.SETDIFFERENCE,lhs, rhs, attributes);
			break;
		case DIFFERENCE:
			val = Expr.Binary(Expr.Binary.Op.SETDIFFERENCE,lhs, rhs, attributes);
			break;
		default:
			internalFailure("unknown binary operator", filename, branch.entry());
			return;
		}

		branch.write(code.target, val);
	}

	protected void transform(Code.BinStringOp code, VcBranch branch) {
		Collection<Attribute> attributes = branch.entry().attributes();
		Expr lhs = branch.read(code.leftOperand);
		Expr rhs = branch.read(code.rightOperand);

		switch (code.kind) {
		case APPEND:
			// do nothing
			break;
		case LEFT_APPEND:
			rhs = Expr.Nary(Expr.Nary.Op.LIST,new Expr[] { rhs }, branch.entry().attributes());
			break;
		case RIGHT_APPEND:
			lhs = Expr.Nary(Expr.Nary.Op.LIST,new Expr[] { lhs }, branch.entry().attributes());
			break;
		default:
			internalFailure("unknown binary operator", filename, branch.entry());
			return;
		}

		branch.write(code.target,
				Expr.Binary(Expr.Binary.Op.LISTAPPEND,lhs, rhs, branch.entry().attributes()));
	}

	protected void transform(Code.Convert code, VcBranch branch) {
		Expr result = branch.read(code.operand);
		// TODO: actually implement some or all coercions?
		branch.write(code.target, result);
	}

	protected void transform(Code.Const code, VcBranch branch) {
		Value val = convert(code.constant, branch.entry());
		branch.write(code.target,
				Expr.Constant(val, branch.entry().attributes()));
	}

	protected void transform(Code.Debug code, VcBranch branch) {
		// do nout
	}

	protected void transform(Code.Dereference code, VcBranch branch) {
		// TODO
	}

	protected void transform(Code.FieldLoad code, VcBranch branch) {
		Collection<Attribute> attributes = branch.entry().attributes();
		// Expr src = branch.read(code.operand);
		// branch.write(code.target, Exprs.FieldOf(src, code.field,
		// attributes));
		ArrayList<String> fields = new ArrayList<String>(code.type.fields()
				.keySet());
		Collections.sort(fields);
		int index = fields.indexOf(code.field);
		Expr src = branch.read(code.operand);
		Expr result = Expr.Load(src, index, branch.entry().attributes());
		branch.write(code.target, result);
	}

	protected void transform(Code.If code, VcBranch falseBranch,
			VcBranch trueBranch) {
		// First, cover true branch
		Expr.Binary trueTest = buildTest(code.op, code.leftOperand,
				code.rightOperand, code.type, trueBranch);
		trueBranch.add(trueTest);
		falseBranch.add(invert(trueTest));
	}

	protected void transform(Code.IfIs code, VcBranch falseBranch,
			VcBranch trueBranch) {
		// TODO
	}

	protected void transform(Code.IndirectInvoke code, VcBranch branch) {
		// TODO
	}

	protected void transform(Code.Invoke code, VcBranch branch)
			throws Exception {
		SyntacticElement entry = branch.entry();
		Collection<Attribute> attributes = entry.attributes();
		int[] code_operands = code.operands;

		if (code.target != Code.NULL_REG) {
			// Need to assume the post-condition holds.
			Block postcondition = findPostcondition(code.name, code.type,
					branch.entry());
			Expr[] operands = new Expr[code_operands.length];
			for (int i = 0; i != code_operands.length; ++i) {
				operands[i] = branch.read(code_operands[i]);
			}
			Expr argument = Expr.Nary(Expr.Nary.Op.TUPLE, operands, attributes);
			branch.write(code.target, Expr.FunCall(toIdentifier(code.name),
					new SyntacticType[0], argument, attributes));

			// Here, we must add a WycsFile Function to represent the function being called, and to prototype it.
			TypePattern from = new TypePattern.Leaf(convert(code.type.params(),entry), null, attributes);
			TypePattern to = new TypePattern.Leaf(convert(code.type.ret(),entry), null, attributes);
			wycsFile.add(wycsFile.new Function(toIdentifier(code.name),
					Collections.EMPTY_LIST, from, to, null));
			
			if (postcondition != null) {
				// operands = Arrays.copyOf(operands, operands.length);
				Expr[] arguments = new Expr[operands.length + 1];
				System.arraycopy(operands, 0, arguments, 1, operands.length);
				arguments[0] = branch.read(code.target);
				Expr constraint = transformExternalBlock(postcondition,
						arguments, branch);
				// assume the post condition holds
				branch.add(constraint);
			}
		}
	}

	protected void transform(Code.Invert code, VcBranch branch) {
		// TODO
	}

	protected void transform(Code.IndexOf code, VcBranch branch) {
		Expr src = branch.read(code.leftOperand);
		Expr idx = branch.read(code.rightOperand);
		branch.write(code.target,
				Expr.IndexOf(src, idx, branch.entry().attributes()));
	}

	protected void transform(Code.LengthOf code, VcBranch branch) {
		Expr src = branch.read(code.operand);
		branch.write(code.target, Expr.Unary(Expr.Unary.Op.LENGTHOF, src,
				branch.entry().attributes()));
	}

	protected void transform(Code.Loop code, VcBranch branch) {
		// FIXME: assume loop invariant?
	}

	protected void transform(Code.Move code, VcBranch branch) {
		branch.write(code.target, branch.read(code.operand));
	}

	protected void transform(Code.NewMap code, VcBranch branch) {
		// TODO
	}

	protected void transform(Code.NewList code, VcBranch branch) {
		int[] code_operands = code.operands;
		Expr[] vals = new Expr[code_operands.length];
		for (int i = 0; i != vals.length; ++i) {
			vals[i] = branch.read(code_operands[i]);
		}
		branch.write(code.target,
				Expr.Nary(Expr.Nary.Op.LIST, vals, branch.entry().attributes()));
	}

	protected void transform(Code.NewSet code, VcBranch branch) {
		int[] code_operands = code.operands;
		Expr[] vals = new Expr[code_operands.length];
		for (int i = 0; i != vals.length; ++i) {
			vals[i] = branch.read(code_operands[i]);
		}
		branch.write(code.target,
				Expr.Nary(Expr.Nary.Op.SET, vals, branch.entry().attributes()));
	}

	protected void transform(Code.NewRecord code, VcBranch branch) {
		int[] code_operands = code.operands;
		Type.Record type = code.type;
		ArrayList<String> fields = new ArrayList<String>(type.fields().keySet());
		Collections.sort(fields);
		Expr[] vals = new Expr[fields.size()];
		for (int i = 0; i != fields.size(); ++i) {
			vals[i] = branch.read(code_operands[i]);
		}

		branch.write(code.target, new Expr.Nary(Expr.Nary.Op.TUPLE, vals,
				branch.entry().attributes()));
	}

	protected void transform(Code.NewObject code, VcBranch branch) {
		// TODO
	}

	protected void transform(Code.NewTuple code, VcBranch branch) {
		int[] code_operands = code.operands;
		Expr[] vals = new Expr[code_operands.length];
		for (int i = 0; i != vals.length; ++i) {
			vals[i] = branch.read(code_operands[i]);
		}
		branch.write(code.target, Expr.Nary(Expr.Nary.Op.TUPLE, vals, branch
				.entry().attributes()));
	}

	protected void transform(Code.Nop code, VcBranch branch) {
		// do nout
	}

	protected void transform(Code.Return code, VcBranch branch) {
		// nothing to do
	}

	protected void transform(Code.SubString code, VcBranch branch) {
		Expr src = branch.read(code.operands[0]);
		Expr start = branch.read(code.operands[1]);
		Expr end = branch.read(code.operands[2]);
		Expr result = Exprs.SubList(src, start, end, branch.entry()
				.attributes());
		branch.write(code.target, result);
	}

	protected void transform(Code.SubList code, VcBranch branch) {
		Expr src = branch.read(code.operands[0]);
		Expr start = branch.read(code.operands[1]);
		Expr end = branch.read(code.operands[2]);
		Expr result = Exprs.SubList(src, start, end, branch.entry()
				.attributes());
		branch.write(code.target, result);
	}

	protected void transform(Code.Throw code, VcBranch branch) {
		// TODO
	}

	protected void transform(Code.TupleLoad code, VcBranch branch) {
		Expr src = branch.read(code.operand);
		Expr result = Expr.Load(src, code.index, branch.entry()
				.attributes());
		branch.write(code.target, result);
	}

	protected void transform(Code.TryCatch code, VcBranch branch) {
		// FIXME: do something here?
	}

	protected void transform(Code.UnArithOp code, VcBranch branch) {
		if (code.kind == Code.UnArithKind.NEG) {
			Expr operand = branch.read(code.operand);
			branch.write(code.target, Expr.Unary(Expr.Unary.Op.NEG, operand,
					branch.entry().attributes()));
		} else {
			// TODO
		}
	}

	protected void transform(Code.Update code, VcBranch branch) {
		Expr result = branch.read(code.operand);
		Expr source = branch.read(code.target);
		branch.write(code.target,
				updateHelper(code.iterator(), source, result, branch));
	}

	protected Expr updateHelper(Iterator<Code.LVal> iter, Expr source,
			Expr result, VcBranch branch) {
		if (!iter.hasNext()) {
			return result;
		} else {
			Collection<Attribute> attributes = branch.entry().attributes();
			Code.LVal lv = iter.next();
			if (lv instanceof Code.RecordLVal) {
				Code.RecordLVal rlv = (Code.RecordLVal) lv;
				// result = updateHelper(iter,
				// Exprs.FieldOf(source, rlv.field, attributes), result,
				// branch);
				// return Exprs.FieldUpdate(source, rlv.field, result,
				// attributes);

				// FIXME: following is broken for open records.
				ArrayList<String> fields = new ArrayList<String>(rlv.rawType()
						.fields().keySet());
				Collections.sort(fields);
				int index = fields.indexOf(rlv.field);
				Expr[] operands = new Expr[fields.size()];
				for (int i = 0; i != fields.size(); ++i) {
					if (i != index) {
						operands[i] = Expr.Load(source, i, attributes);
					} else {
						operands[i] = updateHelper(iter,
								Expr.Load(source, index, attributes),
								result, branch);
					}
				}
				return Expr.Nary(Expr.Nary.Op.TUPLE, operands, attributes);
			} else if (lv instanceof Code.ListLVal) {
				Code.ListLVal rlv = (Code.ListLVal) lv;
				Expr index = branch.read(rlv.indexOperand);
				result = updateHelper(iter,
						Expr.IndexOf(source, index, attributes),
						result, branch);
				
				return Exprs.ListUpdate(source, index, result, attributes);
			} else if (lv instanceof Code.MapLVal) {
				return source; // TODO
			} else if (lv instanceof Code.StringLVal) {
				return source; // TODO
			} else {
				return source; // TODO
			}
		}
	}

	protected Block findPrecondition(NameID name, Type.FunctionOrMethod fun,
			SyntacticElement elem) throws Exception {
		Path.Entry<WyilFile> e = builder.namespace().get(name.module(),
				WyilFile.ContentType);
		if (e == null) {
			syntaxError(
					errorMessage(ErrorMessages.RESOLUTION_ERROR, name.module()
							.toString()), filename, elem);
		}
		WyilFile m = e.read();
		WyilFile.MethodDeclaration method = m.method(name.name(), fun);

		for (WyilFile.Case c : method.cases()) {
			// FIXME: this is a hack for now
			return c.precondition();
		}
		return null;
	}

	protected Block findPostcondition(NameID name, Type.FunctionOrMethod fun,
			SyntacticElement elem) throws Exception {
		Path.Entry<WyilFile> e = builder.namespace().get(name.module(),
				WyilFile.ContentType);
		if (e == null) {
			syntaxError(
					errorMessage(ErrorMessages.RESOLUTION_ERROR, name.module()
							.toString()), filename, elem);
		}
		WyilFile m = e.read();
		WyilFile.MethodDeclaration method = m.method(name.name(), fun);

		for (WyilFile.Case c : method.cases()) {
			// FIXME: this is a hack for now
			return c.postcondition();
		}
		return null;
	}

	/**
	 * Generate a constraint representing an external block (e.g. a
	 * pre/post-condition or invariant).
	 * 
	 * @param externalBlock
	 *            --- the external block of code being translated.
	 * @param prefix
	 *            --- a prefix to use to ensure that local variables to the
	 *            external block will not clash with variables in the branch.
	 * @param operands
	 *            --- operand register in containing branch which should map to
	 *            the inputs of the block being translated.
	 * @param branch
	 *            --- branch into which the resulting constraint is to be
	 *            placed.
	 * @return
	 */
	protected Expr transformExternalBlock(Block externalBlock, Expr[] operands,
			VcBranch branch) {

		// first, generate a constraint representing the post-condition.
		VcBranch master = new VcBranch(externalBlock);

		// second, set initial environment
		for (int i = 0; i != operands.length; ++i) {
			master.write(i, operands[i]);
		}

		return master.transform(new VcTransformer(builder, wycsFile,
				filename, true));
	}

	/**
	 * Generate a formula representing a condition from an Code.IfCode or
	 * Code.Assert bytecodes.
	 * 
	 * @param op
	 * @param stack
	 * @param elem
	 * @return
	 */
	private Expr.Binary buildTest(Code.Comparator cop, int leftOperand,
			int rightOperand, Type type, VcBranch branch) {
		Expr lhs = branch.read(leftOperand);
		Expr rhs = branch.read(rightOperand);
		Expr.Binary.Op op;
		switch (cop) {
		case EQ:
			op = Expr.Binary.Op.EQ;
			break;
		case NEQ:
			op = Expr.Binary.Op.NEQ;
			break;
		case GTEQ:
			op = Expr.Binary.Op.GTEQ;
			break;
		case GT:
			op = Expr.Binary.Op.GT;
			break;
		case LTEQ:
			op = Expr.Binary.Op.LTEQ;
			break;
		case LT:
			op = Expr.Binary.Op.LT;
			break;
		case SUBSET:
			op = Expr.Binary.Op.SUBSET;
			break;
		case SUBSETEQ:
			op = Expr.Binary.Op.SUBSETEQ;
			break;
		case ELEMOF:
			op = Expr.Binary.Op.IN;
			break;
		default:
			internalFailure("unknown comparator (" + cop + ")", filename,
					branch.entry());
			return null;
		}

		return Expr.Binary(op, lhs, rhs, branch.entry().attributes());
	}

	/**
	 * Generate the logically inverted expression corresponding to this
	 * comparator.
	 * 
	 * @param cop
	 * @param leftOperand
	 * @param rightOperand
	 * @param type
	 * @param branch
	 * @return
	 */
	private Expr invert(Expr.Binary test) {
		Expr.Binary.Op op;
		switch (test.op) {
		case EQ:
			op = Expr.Binary.Op.NEQ;
			break;
		case NEQ:
			op = Expr.Binary.Op.EQ;
			break;
		case GTEQ:
			op = Expr.Binary.Op.LT;
			break;
		case GT:
			op = Expr.Binary.Op.LTEQ;
			break;
		case LTEQ:
			op = Expr.Binary.Op.GT;
			break;
		case LT:
			op = Expr.Binary.Op.GTEQ;
			break;
		case SUBSET:
			op = Expr.Binary.Op.SUPSETEQ;
			break;
		case SUBSETEQ:
			op = Expr.Binary.Op.SUPSET;
			break;
		case SUPSET:
			op = Expr.Binary.Op.SUBSETEQ;
			break;
		case SUPSETEQ:
			op = Expr.Binary.Op.SUBSET;
			break;
		case IN:
			op = Expr.Binary.Op.IN;
			return Expr.Unary(
					Expr.Unary.Op.NOT,
					Expr.Binary(op, test.leftOperand, test.rightOperand,
							test.attributes()), test.attributes());
		default:
			internalFailure("unknown comparator (" + test.op + ")", filename,
					test);
			return null;
		}

		return Expr.Binary(op, test.leftOperand, test.rightOperand,
				test.attributes());
	}

	public Value convert(Constant c, SyntacticElement elem) {
		if (c instanceof Constant.Null) {
			// TODO: is this the best translation?
			return wycs.core.Value.Integer(BigInteger.ZERO);
		} else if (c instanceof Constant.Bool) {
			Constant.Bool cb = (Constant.Bool) c;
			return wycs.core.Value.Bool(cb.value);
		} else if (c instanceof Constant.Byte) {
			Constant.Byte cb = (Constant.Byte) c;
			return wycs.core.Value.Integer(BigInteger.valueOf(cb.value));
		} else if (c instanceof Constant.Char) {
			Constant.Char cb = (Constant.Char) c;
			return wycs.core.Value.Integer(BigInteger.valueOf(cb.value));
		} else if (c instanceof Constant.Integer) {
			Constant.Integer cb = (Constant.Integer) c;
			return wycs.core.Value.Integer(cb.value);
		} else if (c instanceof Constant.Rational) {
			Constant.Rational cb = (Constant.Rational) c;
			return wycs.core.Value.Rational(cb.value);
		} else if (c instanceof Constant.Strung) {
			Constant.Strung cb = (Constant.Strung) c;
			String str = cb.value;
			ArrayList<Value> pairs = new ArrayList<Value>();
			for (int i = 0; i != str.length(); ++i) {
				ArrayList<Value> pair = new ArrayList<Value>();
				pair.add(Value.Integer(BigInteger.valueOf(i)));
				pair.add(Value.Integer(BigInteger.valueOf(str.charAt(i))));
				pairs.add(Value.Tuple(pair));
			}
			return Value.Set(pairs);
		} else if (c instanceof Constant.List) {
			Constant.List cb = (Constant.List) c;
			List<Constant> cb_values = cb.values;
			ArrayList<Value> pairs = new ArrayList<Value>();
			for (int i = 0; i != cb_values.size(); ++i) {
				ArrayList<Value> pair = new ArrayList<Value>();
				pair.add(Value.Integer(BigInteger.valueOf(i)));
				pair.add(convert(cb_values.get(i), elem));
				pairs.add(Value.Tuple(pair));
			}
			return Value.Set(pairs);
		} else if (c instanceof Constant.Map) {
			Constant.Map cb = (Constant.Map) c;
			ArrayList<Value> pairs = new ArrayList<Value>();
			for (Map.Entry<Constant, Constant> e : cb.values.entrySet()) {
				ArrayList<Value> pair = new ArrayList<Value>();
				pair.add(convert(e.getKey(), elem));
				pair.add(convert(e.getValue(), elem));
				pairs.add(Value.Tuple(pair));
			}
			return Value.Set(pairs);
		} else if (c instanceof Constant.Set) {
			Constant.Set cb = (Constant.Set) c;
			ArrayList<Value> values = new ArrayList<Value>();
			for (Constant v : cb.values) {
				values.add(convert(v, elem));
			}
			return wycs.core.Value.Set(values);
		} else if (c instanceof Constant.Tuple) {
			Constant.Tuple cb = (Constant.Tuple) c;
			ArrayList<Value> values = new ArrayList<Value>();
			for (Constant v : cb.values) {
				values.add(convert(v, elem));
			}
			return wycs.core.Value.Tuple(values);
		} else {
			internalFailure("unknown constant encountered (" + c + ")",
					filename, elem);
			return null;
		}
	}

	private Pair<SyntacticType,Expr.Variable>[] convertParameters(
			ArrayList<Type> parameters, SyntacticElement element) {
		Pair<SyntacticType,Expr.Variable>[] types = new Pair[parameters.size()];
		for (int i = 0; i != types.length; ++i) {
			SyntacticType t = convert(parameters.get(i), element);
			Expr.Variable v = Expr.Variable("r" + i);
			types[i] = new Pair<SyntacticType,Expr.Variable>(t,v);
		}
		return types;
	}

	private SyntacticType convert(List<Type> types, SyntacticElement elem) {
		SyntacticType[] ntypes = new SyntacticType[types.size()];
		for(int i=0;i!=ntypes.length;++i) {
			ntypes[i] = convert(types.get(i),elem);
		}
		return new SyntacticType.Tuple(ntypes);
	}
	
	private SyntacticType convert(Type t, SyntacticElement elem) {
		// FIXME: this is fundamentally broken in the case of recursive types.
		if (t instanceof Type.Any) {
			return new SyntacticType.Primitive(SemanticType.Any);
		} else if (t instanceof Type.Void) {
			return new SyntacticType.Primitive(SemanticType.Void);
		} else if (t instanceof Type.Bool) {
			return new SyntacticType.Primitive(SemanticType.Bool);
		} else if (t instanceof Type.Char) {
			return new SyntacticType.Primitive(SemanticType.Int);
		} else if (t instanceof Type.Byte) {
			return new SyntacticType.Primitive(SemanticType.Int);
		} else if (t instanceof Type.Int) {
			return new SyntacticType.Primitive(SemanticType.Int);
		} else if (t instanceof Type.Real) {
			return new SyntacticType.Primitive(SemanticType.Real);
		} else if (t instanceof Type.Strung) {
			return new SyntacticType.Primitive(SemanticType.String);
		} else if (t instanceof Type.Set) {
			Type.Set st = (Type.Set) t;
			SyntacticType element = convert(st.element(), elem);
			return new SyntacticType.Set(element);
		} else if (t instanceof Type.Map) {
			Type.Map lt = (Type.Map) t;
			SyntacticType from = convert(lt.key(), elem);
			SyntacticType to = convert(lt.value(), elem);
			// ugly.
			return new SyntacticType.Map(from,to);
		} else if (t instanceof Type.List) {
			Type.List lt = (Type.List) t;
			SyntacticType element = convert(lt.element(), elem);
			// ugly.
			return new SyntacticType.List(element);
		} else if (t instanceof Type.Tuple) {
			Type.Tuple tt = (Type.Tuple) t;
			SyntacticType[] elements = new SyntacticType[tt.size()];
			for (int i = 0; i != tt.size(); ++i) {
				elements[i] = convert(tt.element(i), elem);
			}
			return new SyntacticType.Tuple(elements);
		} else if (t instanceof Type.Record) {
			Type.Record rt = (Type.Record) t;
			// return new SyntacticType.Set(new SyntacticType.Tuple(
			// new SyntacticType[] {
			// new SyntacticType.Primitive(SemanticType.String),
			// new SyntacticType.Primitive(SemanticType.Any) }));
			HashMap<String, Type> fields = rt.fields();
			ArrayList<String> names = new ArrayList<String>(fields.keySet());
			SyntacticType[] elements = new SyntacticType[names.size()];
			Collections.sort(names);
			for (int i = 0; i != names.size(); ++i) {
				String field = names.get(i);
				elements[i] = convert(fields.get(field), elem);
			}
			return new SyntacticType.Tuple(elements);
		} else if (t instanceof Type.Reference) {
			// FIXME: how to translate this??
			return new SyntacticType.Primitive(SemanticType.Any);
		} else {
			internalFailure("unknown type encountered (" + t + ")", filename,
					elem);
			return null;
		}
	}
	
	/**
	 * Convert a wyil NameID into a string that is suitable to be used as a
	 * function name or variable identifier in WycsFiles.
	 * 
	 * @param id
	 * @return
	 */
	private String toIdentifier(NameID id) {
		return id.toString().replace(":","_").replace("/","_");
	}
}