/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.refactoring;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jface.util.Assert;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;

import org.eclipse.jdt.internal.corext.refactoring.base.IChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChange.EditChange;
import org.eclipse.jdt.internal.corext.textmanipulation.TextRange;

/* package */ class PseudoJavaChangeElement extends ChangeElement {

	private IJavaElement fJavaElement;
	private List fChildren;

	public PseudoJavaChangeElement(ChangeElement parent, IJavaElement element) {
		super(parent);
		fJavaElement= element;
		Assert.isNotNull(fJavaElement);
	}
	
	/**
	 * Returns the Java element.
	 * 
	 * @return the Java element managed by this node
	 */
	public IJavaElement getJavaElement() {
		return fJavaElement;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.ui.refactoring.ChangeElement#getChangePreviewViewer()
	 */
	public ChangePreviewViewerDescriptor getChangePreviewViewer() throws CoreException {
		DefaultChangeElement element= getStandardChangeElement();
		if (element == null)
			return null;
		return element.getChangePreviewViewer();
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.ui.refactoring.ChangeElement#feedInput(org.eclipse.jdt.internal.ui.refactoring.IChangePreviewViewer)
	 */
	public void feedInput(IChangePreviewViewer viewer) throws CoreException {
		DefaultChangeElement element= getStandardChangeElement();
		if (element != null) {
			IChange change= element.getChange();
			if (change instanceof TextChange) {
				List edits= collectTextEditChanges();
				viewer.setInput(TextChangePreviewViewer.createInput(
					(EditChange[])edits.toArray(new EditChange[edits.size()]),
					getTextRange()));
			}
		} else {
			viewer.setInput(null);
		}
	}
	
	/* non Java-doc
	 * @see ChangeElement#setActive
	 */
	public void setActive(boolean active) {
		for (Iterator iter= fChildren.iterator(); iter.hasNext();) {
			ChangeElement element= (ChangeElement)iter.next();
			element.setActive(active);
		}
	}
	
	/* non Java-doc
	 * @see ChangeElement.getActive
	 */
	public int getActive() {
		Assert.isTrue(fChildren.size() > 0);
		int result= ((ChangeElement)fChildren.get(0)).getActive();
		for (int i= 1; i < fChildren.size(); i++) {
			ChangeElement element= (ChangeElement)fChildren.get(i);
			result= ACTIVATION_TABLE[element.getActive()][result];
			if (result == PARTLY_ACTIVE)
				break;
		}
		return result;
	}
	
	/* non Java-doc
	 * @see ChangeElement.getChildren
	 */
	public ChangeElement[] getChildren() {
		if (fChildren == null)
			return EMPTY_CHILDREN;
		return (ChangeElement[]) fChildren.toArray(new ChangeElement[fChildren.size()]);
	}
	
	/**
	 * Adds the given <code>TextEditChangeElement<code> as a child to this 
	 * <code>PseudoJavaChangeElement</code>
	 * 
	 * @param child the child to be added
	 */
	public void addChild(TextEditChangeElement child) {
		doAddChild(child);
	}
	
	/**
	 * Adds the given <code>PseudoJavaChangeElement<code> as a child to this 
	 * <code>PseudoJavaChangeElement</code>
	 * 
	 * @param child the child to be added
	 */
	public void addChild(PseudoJavaChangeElement child) {
		doAddChild(child);
	}
	
	private void doAddChild(ChangeElement child) {
		if (fChildren == null)
			fChildren= new ArrayList(2);
		fChildren.add(child);
	}
	
	private DefaultChangeElement getStandardChangeElement() {
		ChangeElement element= getParent();
		while(!(element instanceof DefaultChangeElement) && element != null) {
			element= element.getParent();
		}
		return (DefaultChangeElement)element;
	}
	
	private List collectTextEditChanges() {
		List result= new ArrayList(10);
		ChangeElement[] children= getChildren();
		for (int i= 0; i < children.length; i++) {
			ChangeElement child= children[i];
			if (child instanceof TextEditChangeElement) {
				result.add(((TextEditChangeElement)child).getTextEditChange());
			} else if (child instanceof PseudoJavaChangeElement) {
				result.addAll(((PseudoJavaChangeElement)child).collectTextEditChanges());
			}
		}
		return result;
	}
	
	public TextRange getTextRange() throws CoreException {
		ISourceRange range= ((ISourceReference)fJavaElement).getSourceRange();
		return TextRange.createFromStartAndLength(range.getOffset(), range.getLength());
	}	
}

