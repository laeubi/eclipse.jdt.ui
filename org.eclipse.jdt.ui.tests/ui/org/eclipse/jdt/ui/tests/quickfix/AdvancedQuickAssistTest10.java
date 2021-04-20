/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ui.tests.quickfix;

import java.util.Hashtable;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.eclipse.jdt.testplugin.JavaProjectHelper;
import org.eclipse.jdt.testplugin.TestOptions;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;

import org.eclipse.jdt.ui.tests.core.rules.Java10ProjectTestSetup;
import org.eclipse.jdt.ui.tests.core.rules.ProjectTestSetup;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;

import org.eclipse.jdt.internal.ui.text.correction.AssistContext;
import org.eclipse.jdt.internal.ui.text.correction.CorrectionMessages;

public class AdvancedQuickAssistTest10 extends QuickFixTest {
	@Rule
    public ProjectTestSetup projectSetup= new Java10ProjectTestSetup();

	private IJavaProject fJProject1;

	private IPackageFragmentRoot fSourceFolder;

	@Before
	public void setUp() throws Exception {
		Hashtable<String, String> options= TestOptions.getDefaultOptions();
		JavaCore.setOptions(options);

		fJProject1= projectSetup.getProject();

		fSourceFolder= JavaProjectHelper.addSourceContainer(fJProject1, "src");
	}

	@After
	public void tearDown() throws Exception {
		JavaProjectHelper.clear(fJProject1, projectSetup.getDefaultClasspath());
	}

	@Test
	public void testSplitLocalVarTypeVariable1() throws Exception {
		// 'if' in lambda body - positive cases
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		String buf= "" +
				"package test1;\n\n" +
				"public class Test {\n" +
				"	public static void main(String[] args) {\n" +
				"		\n" +
				"//		comment before\n" +
				"		var x = \"abc\";\n" +
				"//		comment after\n" +
				"		System.out.println(x);	\n" +
				"	}	\n" +
				"}" ;
		ICompilationUnit cu= pack1.createCompilationUnit("Test.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("x");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List<IJavaCompletionProposal> proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= "" +
			"package test1;\n" +
			"\n" +
			"public class Test {\n" +
			"	public static void main(String[] args) {\n" +
			"		\n" +
			"//		comment before\n" +
			"		String x;\n" +
			"		x = \"abc\";\n" +
			"//		comment after\n" +
			"		System.out.println(x);	\n" +
			"	}	\n" +
			"}";

		assertProposalPreviewEquals(buf, CorrectionMessages.QuickAssistProcessor_splitdeclaration_description, proposals);
	}

	@Test
	public void testSplitLocalVarTypeVariable2() throws Exception {
		// 'if' in lambda body - positive cases
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		String buf= "" +
				"package test1;\n\n" +
				"public class Test {\n" +
				"	public static void main(String[] args) {\n" +
				"		\n" +
				"		System.out.println(\"Hello\");\n" +
				"		for (/*var variable*/var x = 0; x< 10 ; x++) {\n" +
				"			\n" +
				"		}\n" +
				"//		comment after\n" +
				"	}	\n" +
				"}" ;
		ICompilationUnit cu= pack1.createCompilationUnit("Test.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("x");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List<IJavaCompletionProposal> proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= "" +
			"package test1;\n" +
			"\n" +
			"public class Test {\n" +
			"	public static void main(String[] args) {\n" +
			"		\n" +
			"		System.out.println(\"Hello\");\n" +
			"		/*var variable*/int x;\n" +
			"		for (x = 0; x< 10 ; x++) {\n" +
			"			\n" +
			"		}\n" +
			"//		comment after\n" +
			"	}	\n" +
			"}";

		assertProposalPreviewEquals(buf, CorrectionMessages.QuickAssistProcessor_splitdeclaration_description, proposals);
	}

	@Test
	public void testSplitLocalVarTypeVariable3() throws Exception {
		// 'if' in lambda body - positive cases
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		String buf= "" +
				"package test1;\n" +
				"\n" +
				"import java.util.Date;\n" +
				"import java.util.HashMap;\n" +
				"import java.util.HashSet;\n" +
				"public class Helper {\n" +
				"	static HashMap<String, HashSet<Date>> getVal(){\n" +
				"		return null;\n" +
				"	}\n" +
				"}" ;
		pack1.createCompilationUnit("Helper.java", buf.toString(), false, null);
		buf= "" +
				"package test1;\n\n" +
				"public class Test {\n" +
				"	public static void main(String[] args) {\n" +
				"		\n" +
				"//		comment before\n" +
				"		var x = Helper.getVal();\n" +
				"//		comment after\n" +
				"		System.out.println(x);	\n" +
				"	}	\n" +
				"}" ;
		ICompilationUnit cu= pack1.createCompilationUnit("Test.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("x");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List<IJavaCompletionProposal> proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= "" +
			"package test1;\n" +
			"\n" +
			"import java.util.Date;\n" +
			"import java.util.HashMap;\n" +
			"import java.util.HashSet;\n" +
			"\n" +
			"public class Test {\n" +
			"	public static void main(String[] args) {\n" +
			"		\n" +
			"//		comment before\n" +
			"		HashMap<String, HashSet<Date>> x;\n" +
			"		x = Helper.getVal();\n" +
			"//		comment after\n" +
			"		System.out.println(x);	\n" +
			"	}	\n" +
			"}";

		assertProposalPreviewEquals(buf, CorrectionMessages.QuickAssistProcessor_splitdeclaration_description, proposals);
	}

	@Test
	public void testSplitLocalVarTypeVariable4() throws Exception {
		// 'if' in lambda body - positive cases
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		String buf= "" +
				"package test1;\n" +
				"\n" +
				"import java.util.Date;\n" +
				"import java.util.HashMap;\n" +
				"import java.util.HashSet;\n" +
				"public class Helper {\n" +
				"	static HashMap<String, HashSet<Date>> getVal(){\n" +
				"		return null;\n" +
				"	}\n" +
				"}" ;
		pack1.createCompilationUnit("Helper.java", buf.toString(), false, null);
		buf= "" +
				"package test1;\n\n" +
				"public class Test {\n" +
				"	public static void main(String[] args) {\n" +
				"		\n" +
				"		System.out.println(\"Hello\");\n" +
				"		for (/*var variable*/var x = Helper.getVal();;) {\n" +
				"			System.out.println(x);\n" +
				"		}\n" +
				"//		comment after\n" +
				"	}	\n" +
				"}" ;
		ICompilationUnit cu= pack1.createCompilationUnit("Test.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("x");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List<IJavaCompletionProposal> proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= "" +
			"package test1;\n" +
			"\n" +
			"import java.util.Date;\n" +
			"import java.util.HashMap;\n" +
			"import java.util.HashSet;\n" +
			"\n" +
			"public class Test {\n" +
			"	public static void main(String[] args) {\n" +
			"		\n" +
			"		System.out.println(\"Hello\");\n" +
			"		/*var variable*/HashMap<String,HashSet<Date>> x;\n" +
			"		for (x = Helper.getVal();;) {\n" +
			"			System.out.println(x);\n" +
			"		}\n" +
			"//		comment after\n" +
			"	}	\n" +
			"}";

		assertProposalPreviewEquals(buf, CorrectionMessages.QuickAssistProcessor_splitdeclaration_description, proposals);
	}
}
