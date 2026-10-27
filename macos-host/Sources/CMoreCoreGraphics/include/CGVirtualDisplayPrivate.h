#ifndef CG_VIRTUAL_DISPLAY_PRIVATE_H
#define CG_VIRTUAL_DISPLAY_PRIVATE_H

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/*
 * Private CoreGraphics Virtual Display API declarations for macOS 14.0+ (Sonoma / Sequoia).
 * Used to instantiate true hardware-accelerated extended virtual monitors with custom
 * HiDPI (@2x) modes, physical millimeter dimensions, and high refresh rates (60-144Hz).
 */

@interface CGVirtualDisplayMode : NSObject
@property(readonly, nonatomic) NSUInteger width;
@property(readonly, nonatomic) NSUInteger height;
@property(readonly, nonatomic) CGFloat refreshRate;
- (instancetype)initWithWidth:(NSUInteger)width
                       height:(NSUInteger)height
                  refreshRate:(CGFloat)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property(retain, nonatomic) NSArray<CGVirtualDisplayMode *> *modes;
@property(nonatomic) unsigned int hiDPI;
@property(nonatomic) unsigned int rotation;
- (instancetype)init;
@end

@interface CGVirtualDisplayDescriptor : NSObject
@property(copy, nonatomic, nullable) NSString *name;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(nonatomic) CGPoint redPrimary;
@property(nonatomic) CGPoint greenPrimary;
@property(nonatomic) CGPoint bluePrimary;
@property(nonatomic) CGPoint whitePoint;
@property(retain, nonatomic, nullable) dispatch_queue_t queue;
@property(copy, nonatomic, nullable) void (^terminationHandler)(id, id);
@property(nonatomic) unsigned int vendorID;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int serialNumber;
- (instancetype)init;
- (nullable dispatch_queue_t)dispatchQueue;
- (void)setDispatchQueue:(nullable dispatch_queue_t)queue;
@end

@interface CGVirtualDisplay : NSObject
@property(readonly, nonatomic) CGDirectDisplayID displayID;
@property(readonly, nonatomic) unsigned int vendorID;
@property(readonly, nonatomic) unsigned int productID;
@property(readonly, nonatomic) unsigned int serialNumber;
@property(readonly, copy, nonatomic, nullable) NSString *name;
@property(readonly, nonatomic) CGSize sizeInMillimeters;
@property(readonly, nonatomic) unsigned int maxPixelsWide;
@property(readonly, nonatomic) unsigned int maxPixelsHigh;
@property(readonly, retain, nonatomic, nullable) NSArray<CGVirtualDisplayMode *> *modes;
@property(readonly, nonatomic) unsigned int hiDPI;
@property(readonly, nonatomic) unsigned int rotation;
- (nullable instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

/*
 * Native Objective-C USB-C AOA v2 Hotplug & High-Speed Bulk Pipe Engine (`IOUSBHostDevice` / `IOUSBHostInterface` / `IOUSBHostPipe`).
 * - Watches IOKit for Android USB devices (`IOServiceAddMatchingNotification`)
 * - Performs AOA v2 Control Handshake (requests 51, 52, 53) with zero ADB or Developer Options
 * - Claims the re-enumerated AOA v2 Accessory interface (`0x18D1:0x2D00`/`0x2D01`) and opens Bulk IN & Bulk OUT pipes
 */
typedef void (^HSUSBAOAStateHandler)(BOOL connected, NSString * _Nullable deviceName);
typedef void (^HSUSBAOAPacketHandler)(NSData *rawChunk);

@interface HSUSBAOAManager : NSObject
@property(copy, nonatomic, nullable) HSUSBAOAStateHandler stateHandler;
@property(copy, nonatomic, nullable) HSUSBAOAPacketHandler packetHandler;
@property(readonly, nonatomic) BOOL isConnected;

- (instancetype)initWithSerialPIN:(NSString *)serialPIN;
- (void)startWatching;
- (void)stopWatching;
- (BOOL)sendBulkData:(NSData *)data;
@end

NS_ASSUME_NONNULL_END

#endif /* CG_VIRTUAL_DISPLAY_PRIVATE_H */
