#!/bin/bash
# Script to apply the fix to laeubi/eclipse.jdt.core and create a PR

set -e

echo "=========================================="
echo "Applying Fix for Issue #2598 to jdt.core"
echo "=========================================="
echo ""

# Check if we're in the right directory
if [ ! -f "fix-issue-2598-tokenmanager-bounds-check.patch" ]; then
    echo "Error: Please run this script from the directory containing the patch file"
    exit 1
fi

PATCH_FILE="$(pwd)/fix-issue-2598-tokenmanager-bounds-check.patch"
JDT_CORE_DIR="../eclipse.jdt.core"

# Check if jdt.core directory exists
if [ ! -d "$JDT_CORE_DIR" ]; then
    echo "Cloning laeubi/eclipse.jdt.core..."
    cd ..
    git clone https://github.com/laeubi/eclipse.jdt.core.git
    cd eclipse.jdt.core
else
    echo "Using existing eclipse.jdt.core directory at $JDT_CORE_DIR"
    cd "$JDT_CORE_DIR"
fi

# Ensure we're on master and up to date
echo ""
echo "Updating master branch..."
git checkout master
git pull origin master

# Create fix branch
echo ""
echo "Creating fix branch..."
git checkout -b fix-issue-2598-tokenmanager-bounds-check

# Apply the patch
echo ""
echo "Applying patch..."
if git apply --check "$PATCH_FILE" 2>/dev/null; then
    git apply "$PATCH_FILE"
    echo "Patch applied successfully!"
else
    echo "Error: Patch could not be applied cleanly."
    echo "Please apply the fix manually using APPLY-FIX-TO-JDT-CORE.md"
    exit 1
fi

# Show the diff
echo ""
echo "Changes made:"
git diff

# Commit the changes
echo ""
echo "Committing changes..."
git add -A
git commit -m "Fix StringIndexOutOfBoundsException in TokenManager.countLineBreaksBetween

Add defensive bounds checking to prevent StringIndexOutOfBoundsException
when startPosition is negative or endPosition exceeds text length.

This fixes the issue where Token.originalEnd can be -2, which causes
start = originalEnd + 1 = -1, leading to an invalid string index access.

Fixes: https://github.com/eclipse-jdt/eclipse.jdt.ui/issues/2598"

# Push to origin
echo ""
echo "Pushing to origin..."
git push -u origin fix-issue-2598-tokenmanager-bounds-check

echo ""
echo "=========================================="
echo "Success! Branch pushed to your fork."
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Go to https://github.com/laeubi/eclipse.jdt.core"
echo "2. You should see a 'Compare & pull request' button"
echo "3. Click it to create a PR to eclipse-jdt/eclipse.jdt.core"
echo ""
echo "Or create a PR manually:"
echo "https://github.com/eclipse-jdt/eclipse.jdt.core/compare/master...laeubi:eclipse.jdt.core:fix-issue-2598-tokenmanager-bounds-check"
echo ""
