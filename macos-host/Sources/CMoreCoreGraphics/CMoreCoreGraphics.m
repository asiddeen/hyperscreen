#import "include/CGVirtualDisplayPrivate.h"
#import <IOKit/IOKitLib.h>
#import <IOKit/usb/IOUSBLib.h>
#import <IOUSBHost/IOUSBHost.h>

void _hs_cmore_coregraphics_anchor(void) {}

@implementation HSUSBAOAManager {
    NSString *_serialPIN;
    dispatch_queue_t _usbQueue;
    IONotificationPortRef _notifyPort;
    io_iterator_t _addedIterator;
    IOUSBHostDevice *_activeDevice;
    IOUSBHostInterface *_activeInterface;
    IOUSBHostPipe *_bulkInPipe;
    IOUSBHostPipe *_bulkOutPipe;
    BOOL _running;
}

- (instancetype)initWithSerialPIN:(NSString *)serialPIN {
    self = [super init];
    if (self) {
        _serialPIN = [serialPIN copy];
        _usbQueue = dispatch_queue_create("dev.hyperscreen.usb.aoa", DISPATCH_QUEUE_SERIAL);
        _running = NO;
    }
    return self;
}

- (BOOL)isConnected {
    return _bulkOutPipe != nil;
}

static void hs_usb_device_added_callback(void *refcon, io_iterator_t iterator) {
    HSUSBAOAManager *manager = (__bridge HSUSBAOAManager *)refcon;
    [manager handleAddedServices:iterator];
}

- (void)startWatching {
    if (_running) return;
    _running = YES;

    _notifyPort = IONotificationPortCreate(kIOMainPortDefault);
    if (!_notifyPort) return;
    IONotificationPortSetDispatchQueue(_notifyPort, _usbQueue);

    CFMutableDictionaryRef matchingDict = IOServiceMatching("IOUSBHostDevice");
    if (!matchingDict) return;

    kern_return_t kr = IOServiceAddMatchingNotification(
        _notifyPort,
        kIOFirstMatchNotification,
        matchingDict,
        hs_usb_device_added_callback,
        (__bridge void *)self,
        &_addedIterator
    );
    if (kr == KERN_SUCCESS && _addedIterator) {
        [self handleAddedServices:_addedIterator];
    }
}

- (void)stopWatching {
    _running = NO;
    if (_addedIterator) {
        IOObjectRelease(_addedIterator);
        _addedIterator = 0;
    }
    if (_notifyPort) {
        IONotificationPortDestroy(_notifyPort);
        _notifyPort = NULL;
    }
    _bulkInPipe = nil;
    _bulkOutPipe = nil;
    if (_activeInterface) {
        [_activeInterface destroy];
        _activeInterface = nil;
    }
    if (_activeDevice) {
        [_activeDevice destroy];
        _activeDevice = nil;
    }
}

- (void)handleAddedServices:(io_iterator_t)iterator {
    io_service_t service;
    while ((service = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        CFNumberRef vidRef = IORegistryEntryCreateCFProperty(service, CFSTR("idVendor"), kCFAllocatorDefault, 0);
        CFNumberRef pidRef = IORegistryEntryCreateCFProperty(service, CFSTR("idProduct"), kCFAllocatorDefault, 0);

        uint16_t vid = 0;
        uint16_t pid = 0;
        if (vidRef) {
            CFNumberGetValue(vidRef, kCFNumberSInt16Type, &vid);
            CFRelease(vidRef);
        }
        if (pidRef) {
            CFNumberGetValue(pidRef, kCFNumberSInt16Type, &pid);
            CFRelease(pidRef);
        }

        if (vid == 0x18D1 && (pid == 0x2D00 || pid == 0x2D04)) {
            // Only claim pure AOA accessory mode (0x2D00 / 0x2D04) so we never block ADB (0x2D01) or USB debugging tunnels
            [self claimAOABulkInterfaceForDeviceService:service];
        }

        IOObjectRelease(service);
    }
}

- (BOOL)isKnownAndroidVendor:(uint16_t)vid {
    if (vid == 0 || vid == 0x05AC) {
        return NO; // Skip invalid or Apple internal USB devices
    }
    return YES; // Probe any non-Apple USB device safely via AOA GET_PROTOCOL (51)
}

- (void)sendAOAv2HandshakeToService:(io_service_t)service {
    NSError *err = nil;
    IOUSBHostDevice *dev = [[IOUSBHostDevice alloc] initWithIOService:service
                                                              options:IOUSBHostObjectInitOptionsNone
                                                                queue:_usbQueue
                                                                error:&err
                                                      interestHandler:nil];
    if (!dev || err) return;

    NSMutableData *protoData = [NSMutableData dataWithLength:2];
    NSUInteger transferred = 0;
    IOUSBDeviceRequest getProto = {
        .bmRequestType = 0xC0,
        .bRequest = 51,
        .wValue = 0,
        .wIndex = 0,
        .wLength = 2
    };
    if (![dev sendDeviceRequest:getProto data:protoData bytesTransferred:&transferred completionTimeout:1.0 error:&err] || transferred < 2) {
        [dev destroy];
        return;
    }

    uint16_t aoaVersion = *(const uint16_t *)protoData.bytes;
    if (aoaVersion < 1) {
        [dev destroy];
        return;
    }

    NSArray<NSString *> *strings = @[
        @"HyperScreen",
        @"HyperScreen Pro Display",
        @"HyperScreen Ultra-Low-Latency Second Display",
        @"1.0",
        @"https://github.com/hyperscreen",
        _serialPIN ?: @"00000001"
    ];

    for (uint16_t idx = 0; idx < strings.count; ++idx) {
        const char *utf8 = strings[idx].UTF8String;
        NSMutableData *strData = [NSMutableData dataWithBytes:utf8 length:strlen(utf8) + 1];
        IOUSBDeviceRequest sendStr = {
            .bmRequestType = 0x40,
            .bRequest = 52,
            .wValue = 0,
            .wIndex = idx,
            .wLength = (uint16_t)strData.length
        };
        [dev sendDeviceRequest:sendStr data:strData bytesTransferred:&transferred completionTimeout:1.0 error:nil];
    }

    IOUSBDeviceRequest startAOA = {
        .bmRequestType = 0x40,
        .bRequest = 53,
        .wValue = 0,
        .wIndex = 0,
        .wLength = 0
    };
    [dev sendDeviceRequest:startAOA data:nil bytesTransferred:&transferred completionTimeout:1.0 error:nil];
    [dev destroy];
}

- (void)claimAOABulkInterfaceForDeviceService:(io_service_t)deviceService {
    io_iterator_t childIter = 0;
    if (IORegistryEntryGetChildIterator(deviceService, kIOServicePlane, &childIter) != KERN_SUCCESS) {
        return;
    }

    io_service_t child;
    while ((child = IOIteratorNext(childIter)) != IO_OBJECT_NULL) {
        if (IOObjectConformsTo(child, "IOUSBHostInterface")) {
            NSError *err = nil;
            IOUSBHostInterface *iface = [[IOUSBHostInterface alloc] initWithIOService:child
                                                                              options:IOUSBHostObjectInitOptionsNone
                                                                                queue:_usbQueue
                                                                                error:&err
                                                                      interestHandler:nil];
            if (iface && !err) {
                const IOUSBConfigurationDescriptor *cfg = iface.configurationDescriptor;
                const IOUSBInterfaceDescriptor *ifDesc = iface.interfaceDescriptor;
                if (cfg && ifDesc && ifDesc->bInterfaceNumber == 0) {
                    const IOUSBEndpointDescriptor *epDesc = NULL;
                    while ((epDesc = IOUSBGetNextEndpointDescriptor(cfg, ifDesc, (const IOUSBDescriptorHeader *)epDesc)) != NULL) {
                        uint8_t transferType = epDesc->bmAttributes & 0x03;
                        if (transferType == kIOUSBEndpointTypeBulk) {
                            uint8_t addr = epDesc->bEndpointAddress;
                            IOUSBHostPipe *pipe = [iface copyPipeWithAddress:addr error:nil];
                            if ((addr & 0x80) != 0) {
                                _bulkInPipe = pipe;
                            } else {
                                _bulkOutPipe = pipe;
                            }
                        }
                    }

                    if (_bulkInPipe && _bulkOutPipe) {
                        _activeInterface = iface;
                        if (_stateHandler) {
                            _stateHandler(YES, @"Android USB-C AOA v2");
                        }
                        [self startBulkReadLoop];
                        IOObjectRelease(child);
                        break;
                    }
                }
                [iface destroy];
            }
        }
        IOObjectRelease(child);
    }
    IOObjectRelease(childIter);
}

- (void)startBulkReadLoop {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
        NSMutableData *readBuf = [NSMutableData dataWithLength:65536];
        while (self->_running && self->_bulkInPipe) {
            NSUInteger bytesRead = 0;
            NSError *err = nil;
            BOOL ok = [self->_bulkInPipe sendIORequestWithData:readBuf
                                              bytesTransferred:&bytesRead
                                             completionTimeout:0.5
                                                         error:&err];
            if (ok && bytesRead > 0 && self->_packetHandler) {
                NSData *chunk = [NSData dataWithBytes:readBuf.bytes length:bytesRead];
                self->_packetHandler(chunk);
            } else if (err && err.code == kIOReturnNotResponding) {
                break;
            }
        }
    });
}

- (BOOL)sendBulkData:(NSData *)data {
    if (!_bulkOutPipe || data.length == 0) return NO;
    NSMutableData *mutableCopy = [data mutableCopy];
    NSUInteger transferred = 0;
    return [_bulkOutPipe sendIORequestWithData:mutableCopy
                              bytesTransferred:&transferred
                             completionTimeout:0.05
                                         error:nil];
}

@end
