# Fix for Issue #2598: StringIndexOutOfBoundsException in Java Formatter

## Issue Summary
Users are experiencing intermittent `StringIndexOutOfBoundsException` errors when saving Java files with formatting enabled in Eclipse 4.38.0 M2.

**Original Issue**: https://github.com/eclipse-jdt/eclipse.jdt.ui/issues/2598

## Root Cause
The bug is located in the **eclipse.jdt.core** repository (not eclipse.jdt.ui), specifically in:
- **File**: `org.eclipse.jdt.core/formatter/org/eclipse/jdt/internal/formatter/TokenManager.java`
- **Method**: `countLineBreaksBetween(String text, int startPosition, int endPosition)` at line 241

### The Problem
When `Token.originalEnd` has a value of -2 or less, the calculation `start = previous.originalEnd + 1` results in -1 or another negative value. The `countLineBreaksBetween` method then tries to access `text.charAt(-1)`, which throws a `StringIndexOutOfBoundsException`.

### Stack Trace
```
java.lang.StringIndexOutOfBoundsException: Index -1 out of bounds for length 2082
  at java.base/java.lang.String.charAt(String.java:1555)
  at org.eclipse.jdt.internal.formatter.TokenManager.countLineBreaksBetween(TokenManager.java:244)
  at org.eclipse.jdt.internal.formatter.linewrap.WrapPreparator.applyBreaksOutsideRegions(WrapPreparator.java:1460)
  at org.eclipse.jdt.internal.formatter.linewrap.WrapPreparator.finishUp(WrapPreparator.java:1394)
  ...
```

## The Fix
The fix adds defensive bounds checking to prevent accessing string indices that are out of bounds:

1. If `startPosition < 0`, set it to 0
2. If `endPosition > text.length()`, set it to `text.length()`
3. If `startPosition >= endPosition`, return 0 (no line breaks to count)

## Applying the Patch

### For eclipse.jdt.core Repository

1. Clone the eclipse.jdt.core repository:
   ```bash
   git clone https://github.com/eclipse-jdt/eclipse.jdt.core.git
   cd eclipse.jdt.core
   ```

2. Apply the patch:
   ```bash
   git apply /path/to/fix-issue-2598-tokenmanager-bounds-check.patch
   ```

3. Or manually apply using:
   ```bash
   patch -p1 < /path/to/fix-issue-2598-tokenmanager-bounds-check.patch
   ```

### Manual Fix (if patch doesn't apply cleanly)

Edit `org.eclipse.jdt.core/formatter/org/eclipse/jdt/internal/formatter/TokenManager.java`:

Find the method `countLineBreaksBetween(String text, int startPosition, int endPosition)` around line 241 and add the bounds checking at the beginning:

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
    // ... rest of the method remains unchanged
}
```

## Testing
After applying the patch:

1. Build the eclipse.jdt.core project
2. Run existing formatter tests to ensure no regressions
3. Test with files that previously caused the error

## Files in this Fix
- `fix-issue-2598-tokenmanager-bounds-check.patch` - The patch file to apply to eclipse.jdt.core
- `ISSUE-2598-FIX.md` - This documentation file

## Next Steps
1. Submit this fix to the eclipse.jdt.core repository
2. Reference this issue (#2598 from eclipse.jdt.ui) in the PR
3. Consider adding a test case to prevent regression

## Additional Notes
- This is a defensive fix that prevents the exception
- The root cause of why `Token.originalEnd` can be -2 may warrant further investigation
- This fix ensures the formatter degrades gracefully instead of crashing
