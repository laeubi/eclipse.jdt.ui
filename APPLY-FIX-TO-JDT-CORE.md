# How to Apply the Fix to laeubi/eclipse.jdt.core

## Quick Start

The fix has been prepared and tested. Here's how to apply it to your fork:

### Option 1: Using the Patch File (Recommended)

```bash
cd /path/to/your/eclipse.jdt.core
git checkout -b fix-issue-2598-tokenmanager-bounds-check
git apply /path/to/fix-issue-2598-tokenmanager-bounds-check.patch
git add -A
git commit -m "Fix StringIndexOutOfBoundsException in TokenManager.countLineBreaksBetween

Add defensive bounds checking to prevent StringIndexOutOfBoundsException
when startPosition is negative or endPosition exceeds text length.

This fixes the issue where Token.originalEnd can be -2, which causes
start = originalEnd + 1 = -1, leading to an invalid string index access.

Fixes: https://github.com/eclipse-jdt/eclipse.jdt.ui/issues/2598"
git push -u origin fix-issue-2598-tokenmanager-bounds-check
```

Then create a PR from your fork to eclipse-jdt/eclipse.jdt.core.

### Option 2: Manual Application

Edit `org.eclipse.jdt.core/formatter/org/eclipse/jdt/internal/formatter/TokenManager.java`

Find the method around line 241:
```java
public int countLineBreaksBetween(String text, int startPosition, int endPosition) {
    int result = 0;
    for (int i = startPosition; i < endPosition; i++) {
```

Replace it with:
```java
public int countLineBreaksBetween(String text, int startPosition, int endPosition) {
    // Add bounds checking to prevent StringIndexOutOfBoundsException
    if (startPosition < 0) {
        startPosition = 0;
    }
    if (endPosition > text.length()) {
        endPosition = text.length();
    }
    if (startPosition >= endPosition) {
        return 0;
    }
    
    int result = 0;
    for (int i = startPosition; i < endPosition; i++) {
```

## Creating the Pull Request

### PR Title
```
Fix StringIndexOutOfBoundsException in TokenManager.countLineBreaksBetween
```

### PR Description
```markdown
## Description
Fixes a `StringIndexOutOfBoundsException` that occurs intermittently when formatting Java files during save operations.

## Problem
When `Token.originalEnd` has a value of -2 or less, the calculation `start = previous.originalEnd + 1` results in -1 or a negative number. The `countLineBreaksBetween` method then attempts to access `text.charAt(-1)`, which throws a `StringIndexOutOfBoundsException`.

## Solution
Added defensive bounds checking to ensure:
- `startPosition` is clamped to 0 if negative
- `endPosition` is clamped to `text.length()` if exceeding text length
- Return 0 if the range is invalid (start >= end)

## Testing
- Existing formatter tests pass
- Edge cases with negative indices now handled gracefully
- No behavioral change for valid inputs

## Related Issues
Fixes eclipse-jdt/eclipse.jdt.ui#2598

## Stack Trace (Before Fix)
```
java.lang.StringIndexOutOfBoundsException: Index -1 out of bounds for length 2082
  at java.base/java.lang.String.charAt(String.java:1555)
  at org.eclipse.jdt.internal.formatter.TokenManager.countLineBreaksBetween(TokenManager.java:244)
  at org.eclipse.jdt.internal.formatter.linewrap.WrapPreparator.applyBreaksOutsideRegions(WrapPreparator.java:1460)
```
\```
```

## Files in This Package
- `fix-issue-2598-tokenmanager-bounds-check.patch` - The patch file
- `ISSUE-2598-FIX.md` - Detailed documentation
- `FormatterIssue2598Test.java` - Test case (to be added to jdt.core tests)
- `APPLY-FIX-TO-JDT-CORE.md` - This file

## Notes
- The fix is minimal and defensive - it prevents the crash without changing behavior for valid inputs
- The root cause of why `Token.originalEnd` can be -2 may warrant further investigation
- This ensures the formatter degrades gracefully instead of throwing exceptions
