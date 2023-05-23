/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
 */

import { NativeModules, Platform, Image } from 'react-native';

const { RNCImageEditor } = NativeModules;

type ImageCropData = {
  /**
   * The top-left corner of the cropped image, specified in the original
   * image's coordinate space.
   */
  offset: { x: number, y: number },
  /**
   * The size (dimensions) of the cropped image, specified in the original
   * image's coordinate space.
   */
  size: { width: number, height: number },
  /**
   * Size to scale the cropped image to.
   */
  displaySize: { width: number, height: number },
};

/* RN.Image.getSize iOS version doesn't seem to return incorrect dimensions thus doesn't require replacement */
const iosGetSize = (uri: string) =>
  new Promise((resolve) => 
    Image.getSize(uri, (width: number, height: number) => resolve({ width, height }))
  )

class ImageEditor {
  static getImageDimensions(uri: string): Promise<{ width: number; height: number; }> {
    return Platform.OS === 'android' ? RNCImageEditor.getImageDimensions(uri) : iosGetSize(uri);
  }

  /**
   * Crop the image specified by the URI param. If URI points to a remote
   * image, it will be downloaded automatically. If the image cannot be
   * loaded/downloaded, the promise will be rejected. On Android, a
   * downloaded image may be cached in external storage, a publicly accessible
   * location, if it has more available space than internal storage.
   *
   * If the cropping process is successful, the resultant cropped image
   * will be stored in the Cache Path, and the URI returned in the promise
   * will point to the image in the cache path. Remember to delete the
   * cropped image from the cache path when you are done with it.
   */
  static cropImage(uri: string, cropData: ImageCropData): Promise<string> {
    return RNCImageEditor.cropImage(uri, cropData);
  }
}

export default ImageEditor;
