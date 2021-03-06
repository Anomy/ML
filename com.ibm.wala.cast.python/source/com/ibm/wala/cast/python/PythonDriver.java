/******************************************************************************
 * Copyright (c) 2018 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *****************************************************************************/
package com.ibm.wala.cast.python;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;

import com.ibm.wala.cast.ipa.callgraph.AstCFAPointerKeys;
import com.ibm.wala.cast.ipa.callgraph.AstContextInsensitiveSSAContextInterpreter;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.python.ipa.callgraph.PythonConstructorTargetSelector;
import com.ibm.wala.cast.python.ipa.callgraph.PythonSSAPropagationCallGraphBuilder;
import com.ibm.wala.cast.python.ipa.callgraph.PythonTrampolineTargetSelector;
import com.ibm.wala.cast.python.ir.PythonLanguage;
import com.ibm.wala.cast.python.loader.PythonLoaderFactory;
import com.ibm.wala.cast.python.types.PythonTypes;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.types.AstMethodReference;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.SourceURLModule;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.ClassHierarchyClassTargetSelector;
import com.ibm.wala.ipa.callgraph.impl.ClassHierarchyMethodTargetSelector;
import com.ibm.wala.ipa.callgraph.impl.ContextInsensitiveSelector;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.cha.SeqClassHierarchyFactory;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.IRFactory;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.Pair;

public class PythonDriver {

	private IClassHierarchy cha;
	
	public PythonDriver(SourceURLModule M) throws ClassHierarchyException {
		ClassLoaderReference dialogs = PythonTypes.pythonLoader;
		
		PythonLoaderFactory loader  = new PythonLoaderFactory();
		
		AnalysisScope test = new AnalysisScope(Collections.singleton(PythonLanguage.Python)) { 
			{
				loadersByName.put(dialogs.getName(), dialogs);
			}
		};
		test.addToScope(dialogs, M);
		
		cha = SeqClassHierarchyFactory.make(test, loader);
	}

	public IClassHierarchy getClassHierarchy() {
		return cha;
	}

	public Pair<CallGraph, PointerAnalysis<InstanceKey>> getCallGraph(String rootScriptName) throws IllegalArgumentException, CancelException {
		AnalysisOptions options = new AnalysisOptions();
		IRFactory<IMethod> irs = AstIRFactory.makeDefaultFactory();

		options.setSelector(new PythonTrampolineTargetSelector(new PythonConstructorTargetSelector(new ClassHierarchyMethodTargetSelector(cha))));
		options.setSelector(new ClassHierarchyClassTargetSelector(cha));

		IClass entry = cha.lookupClass(TypeReference.findOrCreate(PythonTypes.pythonLoader, TypeName.findOrCreate(rootScriptName)));
		MethodReference er = MethodReference.findOrCreate(entry.getReference(), AstMethodReference.fnSelector);
		options.setEntrypoints(Collections.singleton(new DefaultEntrypoint(er, cha)));

		IAnalysisCacheView cache = new AnalysisCacheImpl(irs, options.getSSAOptions());
		PythonSSAPropagationCallGraphBuilder builder = 
				new PythonSSAPropagationCallGraphBuilder(cha, options, cache, new AstCFAPointerKeys());

		AstContextInsensitiveSSAContextInterpreter interpreter = new AstContextInsensitiveSSAContextInterpreter(options, cache);
		builder.setContextInterpreter(interpreter);

		builder.setContextSelector(new ContextInsensitiveSelector());

		builder.setInstanceKeys(new ZeroXInstanceKeys(options, cha, interpreter, ZeroXInstanceKeys.ALLOCATIONS));

		CallGraph CG = builder.makeCallGraph(options);
		PointerAnalysis<InstanceKey> PA = builder.getPointerAnalysis();
		
		return Pair.make(CG, PA);
	}
	
	public static void main(String args[]) throws ClassHierarchyException, IOException, IllegalArgumentException, CancelException {
		File f = new File(args[0]);
		SourceURLModule e = new SourceURLModule(f.exists()? f.toURI().toURL(): new URL(args[0]));
		PythonDriver x = new PythonDriver(e);
		IRFactory<IMethod> irs = AstIRFactory.makeDefaultFactory();
		IClassHierarchy cha = x.getClassHierarchy();
		for(IClass c : cha) {
			System.err.println(c + " : " + c.getName() + " : " + c.getSuperclass());
			for(IField ff : c.getAllStaticFields()) {
				System.err.println(ff);
			}
			for(IMethod m : c.getDeclaredMethods()) {
				System.err.println(m);
			}
		}

		for(IClass c : cha) {
			for(IMethod m : c.getDeclaredMethods()) {
				IR ir = irs.makeIR(m, Everywhere.EVERYWHERE, SSAOptions.defaultOptions());
				for(SSAInstruction inst: ir.getInstructions()) {
					if (inst != null) {
						Position p = ((AstMethod)m).getSourcePosition(inst.iindex);
						if (p !=  null) {
							System.err.println(inst + " : " + new SourceBuffer(p));
						}
					}
				}
			}
		}

		if (args.length > 1) {
			Pair<CallGraph, PointerAnalysis<InstanceKey>> data = x.getCallGraph("Lscript " + args[1]);

			System.err.println(data.snd);
			System.err.println(data.fst);
	
			for(CGNode n : data.fst) {
				System.err.println(n.getIR());
			}
		}
	}
}