// A stand-in header for the doc-comment extractor: clang's JSON AST carries no
// comment nodes, so the generator reads them back out of the source by offset.

@interface WFDocs : NSObject

/// One line, above the declaration.
- (void)single;

/// First line.
/// Second line.
- (void)twoLines;

/**
 A block comment.
 With a second line.
 */
- (void)blockComment;

- (void)undocumented;

@end
