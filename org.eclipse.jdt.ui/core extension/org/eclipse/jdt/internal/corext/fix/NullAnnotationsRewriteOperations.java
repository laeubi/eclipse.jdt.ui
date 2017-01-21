/*******************************************************************************
 * Copyright (c) 2011, 2017 GK Software AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Stephan Herrmann <stephan@cs.tu-berlin.de> - [quick fix] Add quick fixes for null annotations - https://bugs.eclipse.org/337977
 *     IBM Corporation - bug fixes
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.fix;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.text.edits.TextEditGroup;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.fix.CompilationUnitRewriteOperationsFix.CompilationUnitRewriteOperation;
import org.eclipse.jdt.internal.corext.refactoring.structure.CompilationUnitRewrite;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.ui.text.java.IProblemLocation;

public class NullAnnotationsRewriteOperations {

	public enum ChangeKind {
		LOCAL,		// do the normal thing locally in the current method
		INVERSE,	// insert the inverse annotation in the local method
		OVERRIDDEN,	// change the overridden method
		TARGET		// change the target method of a method call
	}

	static abstract class SignatureAnnotationRewriteOperation extends CompilationUnitRewriteOperation {
		// initialized from the Builder:
		CompilationUnit fUnit;
		String fAnnotationToAdd;
		String fAnnotationToRemove;
		boolean fAllowRemove;
		// assigned within constructors:
		protected String fKey;
		protected String fMessage;
		// assigned after the constructor:
		boolean fRemoveIfNonNullByDefault;
		String fNonNullByDefaultName;

		protected SignatureAnnotationRewriteOperation(Builder builder) {
			fUnit= builder.fUnit;
			fAnnotationToAdd= builder.fAnnotationToAdd;
			fAnnotationToRemove= builder.fAnnotationToRemove;
			fAllowRemove= builder.fAllowRemove;
		}

		/* A globally unique key that identifies the position being annotated (for avoiding double annotations). */
		public String getKey() {
			return fKey;
		}

		public CompilationUnit getCompilationUnit() {
			return fUnit;
		}

		boolean checkExisting(List<IExtendedModifier> existingModifiers, ListRewrite listRewrite, TextEditGroup editGroup) {
			for (Object mod : existingModifiers) {
				if (mod instanceof MarkerAnnotation) {
					MarkerAnnotation annotation= (MarkerAnnotation) mod;
					String existingName= annotation.getTypeName().getFullyQualifiedName();
					int lastDot= fAnnotationToRemove.lastIndexOf('.');
					if (existingName.equals(fAnnotationToRemove) || (lastDot != -1 && fAnnotationToRemove.substring(lastDot + 1).equals(existingName))) {
						if (!fAllowRemove)
							return false; // veto this change
						listRewrite.remove(annotation, editGroup);
						return true;
					}
					// paranoia: check if by accident the annotation is already present (shouldn't happen):
					lastDot= fAnnotationToAdd.lastIndexOf('.');
					if (existingName.equals(fAnnotationToAdd) || (lastDot != -1 && fAnnotationToAdd.substring(lastDot + 1).equals(existingName))) {
						return false; // already present
					}
				}
			}
			return true;
		}

		/* Is the given element affected by a @NonNullByDefault. */
		boolean hasNonNullDefault(IBinding enclosingElement) {
			if (!fRemoveIfNonNullByDefault) return false;
			IAnnotationBinding[] annotations = enclosingElement.getAnnotations();
			for (int i= 0; i < annotations.length; i++) {
				IAnnotationBinding annot = annotations[i];
				if (annot.getAnnotationType().getQualifiedName().equals(fNonNullByDefaultName)) {
					IMemberValuePairBinding[] pairs= annot.getDeclaredMemberValuePairs();
					if (pairs.length > 0) {
						// is default cancelled by "false" or "value=false" ?
						for (int j= 0; j < pairs.length; j++)
							if (pairs[j].getKey() == null || pairs[j].getKey().equals("value")) //$NON-NLS-1$
								return (pairs[j].getValue() != Boolean.FALSE);
					}
					return true;
				}
			}
			if (enclosingElement instanceof IMethodBinding) {
				return hasNonNullDefault(((IMethodBinding)enclosingElement).getDeclaringClass());
			} else if (enclosingElement instanceof ITypeBinding) {
				ITypeBinding typeBinding= (ITypeBinding)enclosingElement;
				if (typeBinding.isLocal())
					return hasNonNullDefault(typeBinding.getDeclaringMethod());
				else if (typeBinding.isMember())
					return hasNonNullDefault(typeBinding.getDeclaringClass());
				else
					return hasNonNullDefault(typeBinding.getPackage());
			}
			return false;
		}

		public String getMessage() {
			return fMessage;
		}
	}

	/**
	 * Rewrite operation that inserts an annotation into a method signature.
	 *
	 * Crafted after the lead of Java50Fix.AnnotationRewriteOperation
	 */
	static class ReturnAnnotationRewriteOperation extends SignatureAnnotationRewriteOperation {

		private final MethodDeclaration fBodyDeclaration;

		ReturnAnnotationRewriteOperation(MethodDeclaration method, String message, Builder builder) {
			super(builder);
			fKey= method.resolveBinding().getKey() + "<return>"; //$NON-NLS-1$
			fBodyDeclaration= method;
			fMessage= message;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModel model) throws CoreException {
			AST ast= cuRewrite.getRoot().getAST();
			ListRewrite listRewrite= cuRewrite.getASTRewrite().getListRewrite(fBodyDeclaration, fBodyDeclaration.getModifiersProperty());
			TextEditGroup group= createTextEditGroup(fMessage, cuRewrite);
			if (!checkExisting(fBodyDeclaration.modifiers(), listRewrite, group))
				return;
			if (hasNonNullDefault(fBodyDeclaration.resolveBinding()))
				return; // should be safe, as in this case checkExisting() should've already produced a change (remove existing annotation).
			Annotation newAnnotation= ast.newMarkerAnnotation();
			ImportRewrite importRewrite= cuRewrite.getImportRewrite();
			String resolvableName= importRewrite.addImport(fAnnotationToAdd);
			newAnnotation.setTypeName(ast.newName(resolvableName));
			listRewrite.insertLast(newAnnotation, group); // null annotation is last modifier, directly preceding the return type
		}
	}

	static class ParameterAnnotationRewriteOperation extends SignatureAnnotationRewriteOperation {

		static class IndexedParameter {
			int index;
			String name;
			IndexedParameter(int index, String name) {
				this.index= index;
				this.name= name;
			}
		}

		private SingleVariableDeclaration fArgument;

		// for lambda parameter (can return null):
		static ParameterAnnotationRewriteOperation create(LambdaExpression lambda, IndexedParameter parameter, String message, Builder builder) {
			IMethodBinding lambdaMethodBinding= lambda.resolveMethodBinding();
			List<?> parameters= lambda.parameters();
			if (parameters.size() > parameter.index) {
				Object param= parameters.get(parameter.index);
				if (!(param instanceof SingleVariableDeclaration)) {
					return null; // type elided lambda
				}
				return new ParameterAnnotationRewriteOperation(lambdaMethodBinding, parameters, parameter.index, message, builder);
			}
			// shouldn't happen, we've checked that paramName indeed denotes a parameter.
			throw new RuntimeException("Argument " + parameter.name + " not found in method " + lambda.toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}

		// for method parameter:
		ParameterAnnotationRewriteOperation(MethodDeclaration method, int paramIdx, String message, Builder builder) {
			this(method.resolveBinding(), method.parameters(), paramIdx, message, builder);
		}

		private ParameterAnnotationRewriteOperation(IMethodBinding methodBinding, List<?> parameters, int paramIdx, String message, Builder builder) {
			super(builder);
			fKey= methodBinding.getKey();
			fArgument= (SingleVariableDeclaration) parameters.get(paramIdx);
			fKey+= fArgument.getName().getIdentifier();
			fMessage= message;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModel linkedModel) throws CoreException {
			AST ast= cuRewrite.getRoot().getAST();
			ListRewrite listRewrite= cuRewrite.getASTRewrite().getListRewrite(fArgument, SingleVariableDeclaration.MODIFIERS2_PROPERTY);
			TextEditGroup group= createTextEditGroup(fMessage, cuRewrite);
			if (!checkExisting(fArgument.modifiers(), listRewrite, group))
				return;
			Annotation newAnnotation= ast.newMarkerAnnotation();
			ImportRewrite importRewrite= cuRewrite.getImportRewrite();
			String resolvableName= importRewrite.addImport(fAnnotationToAdd);
			newAnnotation.setTypeName(ast.newName(resolvableName));
			listRewrite.insertLast(newAnnotation, group); // null annotation is last modifier, directly preceding the type
		}
	}

	static class RemoveRedundantAnnotationRewriteOperation extends CompilationUnitRewriteOperation {

		private IProblemLocation fProblem;
		private CompilationUnit fCompilationUnit;

		public RemoveRedundantAnnotationRewriteOperation(CompilationUnit compilationUnit, IProblemLocation problem) {
			fCompilationUnit= compilationUnit;
			fProblem= problem;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModel linkedModel) throws CoreException {
			TextEditGroup group= createTextEditGroup(FixMessages.NullAnnotationsRewriteOperations_remove_redundant_nullness_annotation, cuRewrite);
			ASTRewrite astRewrite= cuRewrite.getASTRewrite();

			CompilationUnit astRoot= fCompilationUnit;
			ASTNode selectedNode= fProblem.getCoveringNode(astRoot);

			if (fProblem.getProblemId() == IProblem.RedundantNullAnnotation) {
				List<IExtendedModifier> modifiers;
				if (selectedNode instanceof SingleVariableDeclaration) {
					SingleVariableDeclaration singleVariableDeclaration= (SingleVariableDeclaration) selectedNode;
					modifiers= singleVariableDeclaration.modifiers();
				} else if (selectedNode instanceof MethodDeclaration) {
					MethodDeclaration methodDeclaration= (MethodDeclaration) selectedNode;
					modifiers= methodDeclaration.modifiers();
				} else {
					return;
				}

				for (Iterator<IExtendedModifier> iterator= modifiers.iterator(); iterator.hasNext();) {
					IExtendedModifier modifier= iterator.next();
					if (modifier instanceof MarkerAnnotation) {
						MarkerAnnotation annotation= (MarkerAnnotation) modifier;
						IAnnotationBinding annotationBinding= annotation.resolveAnnotationBinding();
						String name= annotationBinding.getName();
						if (name.equals(NullAnnotationsFix.getNonNullAnnotationName(fCompilationUnit.getJavaElement(), true))) {
							astRewrite.remove(annotation, group);
						}
					}
				}
			} else {
				if (!(selectedNode instanceof MarkerAnnotation))
					return;
				MarkerAnnotation annotation= (MarkerAnnotation) selectedNode;
				IAnnotationBinding annotationBinding= annotation.resolveAnnotationBinding();
				String name= annotationBinding.getName();
				if (name.equals(NullAnnotationsFix.getNonNullByDefaultAnnotationName(fCompilationUnit.getJavaElement(), true))) {
					astRewrite.remove(annotation, group);
				}
			}
		}
	}

	public static class Builder {

		CompilationUnit fUnit;
		String fAnnotationToAdd;
		String fAnnotationToRemove;
		boolean fAllowRemove;
		boolean fAffectsParameter;

		public Builder(CompilationUnit unit, String annotationToAdd, String annotationToRemove, boolean allowRemove, boolean affectsParameter) {
			fUnit= unit;
			fAnnotationToAdd= annotationToAdd;
			fAnnotationToRemove= annotationToRemove;
			fAllowRemove= allowRemove;
			fAffectsParameter= affectsParameter;
		}

		public void swapAnnotations() {
			String tmp= fAnnotationToAdd;
			fAnnotationToAdd= fAnnotationToRemove;
			fAnnotationToRemove= tmp;
		}

		public boolean is50OrHigher() {
			CompilationUnit compilationUnit= fUnit;
			ICompilationUnit cu= (ICompilationUnit) compilationUnit.getJavaElement();
			return JavaModelUtil.is50OrHigher(cu.getJavaProject());
		}

		public ASTNode getCoveringNode(IProblemLocation problem) {
			return problem.getCoveringNode(fUnit);
		}

		// Entry for QuickFixes:
		public SignatureAnnotationRewriteOperation createAddAnnotationOperation(IProblemLocation problem, Set<String> handledPositions, boolean thisUnitOnly, ChangeKind changeKind) {
			// precondition:
			// thisUnitOnly => changeKind == LOCAL
			SignatureAnnotationRewriteOperation result;
			if (changeKind == ChangeKind.OVERRIDDEN)
				result= createAddAnnotationToOverriddenOperation(problem);
			else
				result= createAddAnnotationOperation(problem, changeKind == ChangeKind.TARGET, thisUnitOnly);
			if (handledPositions != null && result != null) {
				if (handledPositions.contains(result.getKey()))
					return null;
				handledPositions.add(result.getKey());
			}
			return result;
		}

		private SignatureAnnotationRewriteOperation createAddAnnotationOperation(IProblemLocation problem, boolean changeTargetMethod, boolean thisUnitOnly) {
			if (!is50OrHigher())
				return null;

			ASTNode selectedNode= getCoveringNode(problem);
			if (selectedNode == null)
				return null;

			ICompilationUnit cu= (ICompilationUnit) fUnit.getJavaElement();
			ASTNode declaringNode= getDeclaringNode(selectedNode);

			switch (problem.getProblemId()) {
				case IProblem.IllegalDefinitionToNonNullParameter:
//			case IllegalRedefinitionToNonNullParameter:
					// these affect another method
					break;
				case IProblem.IllegalReturnNullityRedefinition:
					if (declaringNode == null)
						declaringNode= selectedNode;
					break; // do propose changes even if we already have an annotation
				default:
					// if this method has annotations, don't change'em
					if (!fAllowRemove && NullAnnotationsFix.hasExplicitNullAnnotation(cu, problem.getOffset()))
						return null;
			}

			String annotationNameLabel= fAnnotationToAdd;
			int lastDot= annotationNameLabel.lastIndexOf('.');
			if (lastDot != -1)
				annotationNameLabel= annotationNameLabel.substring(lastDot + 1);
			annotationNameLabel= BasicElementLabels.getJavaElementName(annotationNameLabel);

			if (changeTargetMethod) {
				MethodInvocation methodInvocation= null;
				if (fAffectsParameter) {
					if (selectedNode.getParent() instanceof MethodInvocation)
						methodInvocation= (MethodInvocation) selectedNode.getParent();
				} else {
					if (selectedNode instanceof MethodInvocation)
						methodInvocation= (MethodInvocation) selectedNode;
				}
				if (methodInvocation != null) {
					// DefiniteNullToNonNullParameter || PotentialNullToNonNullParameter
					int paramIdx= methodInvocation.arguments().indexOf(selectedNode);
					IMethodBinding methodBinding= methodInvocation.resolveMethodBinding();
					MethodDeclaration methodDecl= findMethodDeclarationInUnit(cu, methodBinding, thisUnitOnly);
					if (methodDecl == null)
						return null;
					if (fAffectsParameter) {
						String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_target_method_parameter_nullness,
								new Object[] {methodInvocation.getName(), annotationNameLabel});
						return new ParameterAnnotationRewriteOperation(methodDecl, paramIdx, message, this);
					} else {
						MethodDeclaration declaration = methodDecl;
						String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_method_return_nullness,
								new String[] { declaration.getName().getIdentifier(), annotationNameLabel });
						return new ReturnAnnotationRewriteOperation(declaration, message, this);
					}
				}
			} else if (declaringNode instanceof MethodDeclaration || declaringNode instanceof LambdaExpression) {
				// complaint is in signature of this method / lambda
				switch (problem.getProblemId()) {
					case IProblem.ParameterLackingNonNullAnnotation:
					case IProblem.ParameterLackingNullableAnnotation:
					case IProblem.IllegalDefinitionToNonNullParameter:
					case IProblem.IllegalRedefinitionToNonNullParameter:
						// problems regarding the argument declaration:
						ParameterAnnotationRewriteOperation.IndexedParameter parameter= findParameterDeclaration(selectedNode);
						if (parameter != null) {
							switch (declaringNode.getNodeType()) {
								case ASTNode.METHOD_DECLARATION:
									MethodDeclaration method= (MethodDeclaration) declaringNode;
									String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_method_parameter_nullness,
											new Object[] {parameter.name, annotationNameLabel});
									return new ParameterAnnotationRewriteOperation(method, parameter.index, message, this);
								case ASTNode.LAMBDA_EXPRESSION:
									LambdaExpression lambda = (LambdaExpression) declaringNode;
									// TODO: specific message for lambda
									message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_method_parameter_nullness,
											new Object[] {parameter.name, annotationNameLabel});
									return ParameterAnnotationRewriteOperation.create(lambda, parameter, message, this);
								default:
									return null;
							}
						}
						break;
					case IProblem.SpecdNonNullLocalVariableComparisonYieldsFalse:
					case IProblem.RedundantNullCheckOnSpecdNonNullLocalVariable:
					case IProblem.RequiredNonNullButProvidedNull:
					case IProblem.RequiredNonNullButProvidedPotentialNull:
					case IProblem.RequiredNonNullButProvidedSpecdNullable:
					case IProblem.RequiredNonNullButProvidedUnknown:
					case IProblem.ConflictingNullAnnotations:
					case IProblem.ConflictingInheritedNullAnnotations:
						if (fAffectsParameter) {
							// statement suggests changing parameters:
							if (selectedNode instanceof SimpleName) {
								parameter= findReferencedParameter(selectedNode);
								if (parameter != null) {
									switch (declaringNode.getNodeType()) {
										case ASTNode.METHOD_DECLARATION:
											MethodDeclaration declaration= (MethodDeclaration) declaringNode;
											String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_method_parameter_nullness,
													new Object[] { parameter.name, annotationNameLabel });
											return new ParameterAnnotationRewriteOperation(declaration, parameter.index, message, this);
										case ASTNode.LAMBDA_EXPRESSION:
											LambdaExpression lambda= (LambdaExpression) declaringNode;
											// TODO: appropriate message for lambda
											message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_method_parameter_nullness,
													new Object[] { parameter.name, annotationNameLabel });
											return ParameterAnnotationRewriteOperation.create(lambda, parameter, message, this);
										default:
											return null;
									}
								}
							}
							break;
						}
						//$FALL-THROUGH$
					case IProblem.IllegalReturnNullityRedefinition:
						if (declaringNode.getNodeType()== ASTNode.METHOD_DECLARATION) {
							MethodDeclaration declaration= (MethodDeclaration) declaringNode;
							String name= declaration.getName().getIdentifier();
							String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_method_return_nullness, new String[] { name, annotationNameLabel });
							return new ReturnAnnotationRewriteOperation(declaration, message, this);
						}
				}
			}
			return null;
		}

		private SignatureAnnotationRewriteOperation createAddAnnotationToOverriddenOperation(IProblemLocation problem) {
			if (!is50OrHigher())
				return null;

			ASTNode selectedNode= getCoveringNode(problem);
			if (selectedNode == null)
				return null;

			ICompilationUnit cu= (ICompilationUnit) fUnit.getJavaElement();
			ASTNode declaringNode= getDeclaringNode(selectedNode);
			switch (problem.getProblemId()) {
				case IProblem.IllegalDefinitionToNonNullParameter:
				case IProblem.IllegalRedefinitionToNonNullParameter:
					break;
				case IProblem.IllegalReturnNullityRedefinition:
					if (declaringNode == null)
						declaringNode= selectedNode;
					break;
				default:
					return null;
			}

			String annotationNameLabel= fAnnotationToAdd;
			int lastDot= annotationNameLabel.lastIndexOf('.');
			if (lastDot != -1)
				annotationNameLabel= annotationNameLabel.substring(lastDot + 1);
			annotationNameLabel= BasicElementLabels.getJavaElementName(annotationNameLabel);

			if (declaringNode instanceof MethodDeclaration) {
				// complaint is in signature of this method
				MethodDeclaration declaration= (MethodDeclaration) declaringNode;
				switch (problem.getProblemId()) {
					case IProblem.IllegalDefinitionToNonNullParameter:
					case IProblem.IllegalRedefinitionToNonNullParameter:
						return createChangeOverriddenParameterOperation(cu, declaration, selectedNode, annotationNameLabel);
					case IProblem.IllegalReturnNullityRedefinition:
						if (hasNullAnnotation(declaration)) { // don't adjust super if local has no explicit annotation (?)
							return createChangeOverriddenReturnOperation(cu, declaration, annotationNameLabel);
						}
				}
			}
			return null;
		}

		private SignatureAnnotationRewriteOperation createChangeOverriddenParameterOperation(ICompilationUnit cu, MethodDeclaration declaration, ASTNode selectedNode,
				String annotationNameLabel) {
			IMethodBinding methodDeclBinding= declaration.resolveBinding();
			if (methodDeclBinding == null)
				return null;

			IMethodBinding overridden= Bindings.findOverriddenMethod(methodDeclBinding, false);
			if (overridden == null)
				return null;
			MethodDeclaration overriddenDeclaration= findMethodDeclarationInUnit(cu, overridden, false);
			if (overriddenDeclaration == null)
				return null;
			String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_overridden_parameter_nullness, new String[] { overridden.getName(), annotationNameLabel });
			ParameterAnnotationRewriteOperation.IndexedParameter parameter= findParameterDeclaration(selectedNode); // parameter.name is determined from the current method, but this name will not be used here
			if (parameter == null)
				return null;
			return new ParameterAnnotationRewriteOperation(overriddenDeclaration, parameter.index, message, this);
		}

		private ParameterAnnotationRewriteOperation.IndexedParameter findParameterDeclaration(ASTNode selectedNode) {
			VariableDeclaration argDecl= (selectedNode instanceof VariableDeclaration) ? (VariableDeclaration) selectedNode : (VariableDeclaration) ASTNodes.getParent(selectedNode,
					VariableDeclaration.class);
			if (argDecl != null) {
				StructuralPropertyDescriptor locationInParent= argDecl.getLocationInParent();
				if (!locationInParent.isChildListProperty())
					return null;
				List<?> containingList= (List<?>) argDecl.getParent().getStructuralProperty(locationInParent);
				return new ParameterAnnotationRewriteOperation.IndexedParameter(containingList.indexOf(argDecl), argDecl.getName().getIdentifier());
			}
			return null;
		}

		private ParameterAnnotationRewriteOperation.IndexedParameter findReferencedParameter(ASTNode selectedNode) {
			if (selectedNode.getNodeType() == ASTNode.SIMPLE_NAME) {
				IBinding binding= ((SimpleName) selectedNode).resolveBinding();
				if (binding.getKind() == IBinding.VARIABLE && ((IVariableBinding) binding).isParameter()) {
					ASTNode current= selectedNode.getParent();
					while (current != null) {
						List<?> parameters= null;
						switch (current.getNodeType()) {
							case ASTNode.METHOD_DECLARATION:
								parameters= ((MethodDeclaration) current).parameters();
								break;
							case ASTNode.LAMBDA_EXPRESSION:
								parameters= ((LambdaExpression) current).parameters();
								break;
							default:
								/* continue traversing outwards */
						}
						if (parameters != null) {
							for (int i= 0; i < parameters.size(); i++) {
								VariableDeclaration parameter= (VariableDeclaration) parameters.get(i);
								if (parameter.resolveBinding() == binding)
									return new ParameterAnnotationRewriteOperation.IndexedParameter(i, binding.getName());
							}
						}
						current= current.getParent();
					}
				}
			}
			return null;
		}

		private boolean hasNullAnnotation(MethodDeclaration decl) {
			List<IExtendedModifier> modifiers= decl.modifiers();
			String nonnull= NullAnnotationsFix.getNonNullAnnotationName(decl.resolveBinding().getJavaElement(), false);
			String nullable= NullAnnotationsFix.getNullableAnnotationName(decl.resolveBinding().getJavaElement(), false);
			for (Object mod : modifiers) {
				if (mod instanceof Annotation) {
					Name annotationName= ((Annotation) mod).getTypeName();
					String fullyQualifiedName= annotationName.getFullyQualifiedName();
					if (annotationName.isSimpleName() ? nonnull.endsWith(fullyQualifiedName) : fullyQualifiedName.equals(nonnull))
						return true;
					if (annotationName.isSimpleName() ? nullable.endsWith(fullyQualifiedName) : fullyQualifiedName.equals(nullable))
						return true;
				}
			}
			return false;
		}

		private SignatureAnnotationRewriteOperation createChangeOverriddenReturnOperation(ICompilationUnit cu, MethodDeclaration declaration, String annotationNameLabel) {
			IMethodBinding methodDeclBinding= declaration.resolveBinding();
			if (methodDeclBinding == null)
				return null;

			IMethodBinding overridden= Bindings.findOverriddenMethod(methodDeclBinding, false);
			if (overridden == null)
				return null;
			declaration= findMethodDeclarationInUnit(cu, overridden, false);
			if (declaration == null)
				return null;
// TODO(SH): decide whether we want to propose overwriting existing annotations in super
//		if (hasNullAnnotation(declaration)) // if overridden has explicit declaration don't propose to change it
//			return null;
			String message= Messages.format(FixMessages.NullAnnotationsRewriteOperations_change_overridden_return_nullness, new String[] { overridden.getName(), annotationNameLabel });
			return new ReturnAnnotationRewriteOperation(declaration, message, this);
		}

		private MethodDeclaration findMethodDeclarationInUnit(ICompilationUnit cu, IMethodBinding method, boolean sameUnitOnly) {
			CompilationUnit compilationUnit= findCUForMethod(fUnit, cu, method);
			if (compilationUnit == null)
				return null;
			if (sameUnitOnly && !compilationUnit.getJavaElement().equals(cu))
				return null;
			ASTNode methodDecl= compilationUnit.findDeclaringNode(method.getKey());
			if (methodDecl == null)
				return null;
			fUnit= compilationUnit;
			return (MethodDeclaration) methodDecl;
		}

		private CompilationUnit findCUForMethod(CompilationUnit compilationUnit, ICompilationUnit cu, IMethodBinding methodBinding) {
			ASTNode methodDecl= compilationUnit.findDeclaringNode(methodBinding.getMethodDeclaration());
			if (methodDecl == null) {
				// is methodDecl defined in another CU?
				ITypeBinding declaringTypeDecl= methodBinding.getDeclaringClass().getTypeDeclaration();
				if (declaringTypeDecl.isFromSource()) {
					ICompilationUnit targetCU= null;
					try {
						targetCU= ASTResolving.findCompilationUnitForBinding(cu, compilationUnit, declaringTypeDecl);
					} catch (JavaModelException e) { /* can't do better */
					}
					if (targetCU != null) {
						return ASTResolving.createQuickFixAST(targetCU, null);
					}
				}
				return null;
			}
			return compilationUnit;
		}

		/* The relevant declaring node of a return statement is the enclosing method or lambda. */
		private static ASTNode getDeclaringNode(ASTNode selectedNode) {
			while (selectedNode != null) {
				switch (selectedNode.getNodeType()) {
					case ASTNode.METHOD_DECLARATION:
					case ASTNode.LAMBDA_EXPRESSION:
						return selectedNode;
					default:
						selectedNode= selectedNode.getParent();
				}
			}
			return null;
		}
	}
}
