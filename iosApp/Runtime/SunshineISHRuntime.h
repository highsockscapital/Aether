#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^SunshineISHProgressBlock)(NSString *phase, NSString *detail, double fraction);
typedef void (^SunshineISHFileWriteProgressBlock)(NSUInteger bytesCopied);
typedef void (^SunshineISHCompletionBlock)(NSError * _Nullable error);
typedef void (^SunshineISHOutputBlock)(NSData *bytes);
typedef void (^SunshineISHExitBlock)(int exitCode, int signal);

@interface SunshineISHRuntime : NSObject

+ (instancetype)sharedRuntime;

@property(nonatomic, readonly, getter=isInitialized) BOOL initialized;

- (void)initializeWithProgress:(SunshineISHProgressBlock)progress
                    completion:(SunshineISHCompletionBlock)completion;

- (int)startExecutable:(NSString *)executable
             arguments:(NSArray<NSString *> *)arguments
           environment:(NSDictionary<NSString *, NSString *> *)environment
      workingDirectory:(NSString *)workingDirectory
        pseudoTerminal:(BOOL)pseudoTerminal
    remoteDebuggingPipe:(BOOL)remoteDebuggingPipe
        standardOutput:(SunshineISHOutputBlock)stdoutBlock
         standardError:(SunshineISHOutputBlock)stderrBlock
                   exit:(SunshineISHExitBlock)exitBlock;

- (BOOL)writeStdin:(NSData *)bytes processId:(int)processId;
- (void)closeStdinForProcessId:(int)processId;
- (void)signalProcessId:(int)processId signal:(int)signal;
- (void)resizeTerminalForProcessId:(int)processId columns:(int)columns rows:(int)rows;

- (BOOL)fileExists:(NSString *)path;
- (nullable NSData *)readFile:(NSString *)path error:(NSError **)error;
- (nullable NSData *)readFile:(NSString *)path
                 maximumBytes:(NSUInteger)maximumBytes
                        error:(NSError **)error;
- (nullable NSData *)readFilePrefix:(NSString *)path
                       maximumBytes:(NSUInteger)maximumBytes
                              error:(NSError **)error;
- (BOOL)writeFile:(NSString *)path data:(NSData *)data executable:(BOOL)executable error:(NSError **)error;
- (BOOL)writeFile:(NSString *)path
             data:(NSData *)data
       executable:(BOOL)executable
         progress:(nullable SunshineISHFileWriteProgressBlock)progress
            error:(NSError **)error;
- (BOOL)createDirectories:(NSString *)path error:(NSError **)error;
- (nullable NSArray<NSDictionary<NSString *, id> *> *)listDirectory:(NSString *)path
                                                               error:(NSError **)error;
- (BOOL)movePath:(NSString *)sourcePath toPath:(NSString *)destinationPath error:(NSError **)error;
- (BOOL)removePath:(NSString *)path recursive:(BOOL)recursive error:(NSError **)error;
- (BOOL)bindHostPath:(NSString *)hostPath guestPath:(NSString *)guestPath readOnly:(BOOL)readOnly error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
