// Reuse the validated one-reference codec path. Scratch files are private and
// bounded to one stripe. No system-gallery URI is passed to this library.
#define main rhythm_probe_main
#include "../../../../../experiments/native/reference_probe.cpp"
#undef main
#include <jni.h>
#include <android/bitmap.h>
#include <cmath>
#include <turbojpeg.h>
#ifdef RHYTHM_DAV1D
#include <dav1d/dav1d.h>
struct DavContext {
  Dav1dContext* context=nullptr;
  DavContext(){
    Dav1dSettings settings;dav1d_default_settings(&settings);
    settings.n_threads=1;settings.max_frame_delay=1;settings.frame_size_limit=4096*512;
    if(dav1d_open(&context,&settings))throw std::runtime_error("dav1d initialization failed");
  }
  ~DavContext(){if(context)dav1d_close(&context);}
};
struct DavPacket {
  Dav1dData data{};
  ~DavPacket(){dav1d_data_unref(&data);}
};
struct DavPicture {
  Dav1dPicture picture{};
  ~DavPicture(){dav1d_picture_unref(&picture);}
};
static void dav_frame(DavContext& decoder,const Bytes& bytes,DavPicture& out){
  DavPacket packet;
  auto* buffer=dav1d_data_create(&packet.data,bytes.size());
  if(!buffer)throw std::runtime_error("dav1d packet allocation");
  std::memcpy(buffer,bytes.data(),bytes.size());
  if(dav1d_send_data(decoder.context,&packet.data)<0||dav1d_get_picture(decoder.context,&out.picture)<0)
    throw std::runtime_error("dav1d frame decode failed");
}
static Bytes dav_pixels(const std::string& frame,const std::string& anchor,int w,int h){
  DavContext decoder;
  if(!anchor.empty()){DavPicture reference;dav_frame(decoder,read(anchor),reference);}
  DavPicture image;dav_frame(decoder,read(frame),image);
  const auto& p=image.picture;
  if(p.p.w!=w||p.p.h!=h||p.p.bpc!=8||p.p.layout!=DAV1D_PIXEL_LAYOUT_I444)
    throw std::runtime_error("dav1d decoded layout mismatch");
  Bytes pixels(static_cast<size_t>(w)*h*3);
  for(int plane=0;plane<3;plane++)for(int y=0;y<h;y++)
    std::memcpy(pixels.data()+static_cast<size_t>(plane*w*h+y*w),
      static_cast<unsigned char*>(p.data[plane])+y*p.stride[plane?1:0],w);
  return pixels;
}
#endif

static std::string text(JNIEnv* env, jstring value) {
  if (!value) throw std::runtime_error("Missing string");
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) throw std::runtime_error("String allocation");
  std::string out(chars); env->ReleaseStringUTFChars(value, chars); return out;
}
static void fail(JNIEnv* env, const std::exception& error) {
  env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
struct BitmapLock {
  JNIEnv* env; jobject bitmap; AndroidBitmapInfo info{}; unsigned char* data=nullptr;
  BitmapLock(JNIEnv* e,jobject b,bool full=false):env(e),bitmap(b) {
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=0 ||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 ||
       info.width<1 || info.width>4096 || info.height<1 || info.height>(full?8192u:512u) ||
       static_cast<uint64_t>(info.width)*info.height>12000000)
      throw std::runtime_error("Expected bounded RGBA stripe");
    void* ptr=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&ptr)!=0)throw std::runtime_error("Bitmap lock");
    data=static_cast<unsigned char*>(ptr);
  }
  ~BitmapLock(){if(data)AndroidBitmap_unlockPixels(env,bitmap);}
};
static Bytes aom_pixels(const std::string& frame,const std::string& bootstrap,const std::string& anchor,int w,int h){
  std::unique_ptr<Image> reference;
  if(!anchor.empty())reference=load_anchor(anchor,w,h);
  Context d;decoder(d);
  if(reference){decode(d,read(bootstrap));set_reference(d,reference->im);reference.reset();}
  auto* image=decode(d,read(frame));
  if(image->d_w!=static_cast<unsigned>(w)||image->d_h!=static_cast<unsigned>(h)||
      image->x_chroma_shift||image->y_chroma_shift||(image->fmt&AOM_IMG_FMT_HIGHBITDEPTH))
    throw std::runtime_error("Decoded layout mismatch");
  Bytes pixels(static_cast<size_t>(w)*h*3);
  for(int p=0;p<3;p++)for(int y=0;y<h;y++)
    std::memcpy(pixels.data()+static_cast<size_t>(p*w*h+y*w),image->planes[p]+y*image->stride[p],w);
  return pixels;
}
static std::string pixel_hash(JNIEnv* env,Bytes& pixels){
  auto klass=env->FindClass("java/security/MessageDigest");
  auto algorithm=env->NewStringUTF("SHA-256");
  auto instance=env->GetStaticMethodID(klass,"getInstance","(Ljava/lang/String;)Ljava/security/MessageDigest;");
  auto digest=env->CallStaticObjectMethod(klass,instance,algorithm);
  auto buffer=env->NewDirectByteBuffer(pixels.data(),pixels.size());
  env->CallVoidMethod(digest,env->GetMethodID(klass,"update","(Ljava/nio/ByteBuffer;)V"),buffer);
  auto result=static_cast<jbyteArray>(env->CallObjectMethod(digest,env->GetMethodID(klass,"digest","()[B")));
  if(env->ExceptionCheck()||!result)throw std::runtime_error("Pixel digest failed");
  jbyte bytes[32];env->GetByteArrayRegion(result,0,32,bytes);
  std::string hash;hash.reserve(64);const char* digits="0123456789abcdef";
  for(auto byte:bytes){unsigned value=static_cast<unsigned char>(byte);hash+=digits[value>>4];hash+=digits[value&15];}
  env->DeleteLocalRef(result);env->DeleteLocalRef(buffer);env->DeleteLocalRef(digest);
  env->DeleteLocalRef(algorithm);env->DeleteLocalRef(klass);
  return hash;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_decodeInto(JNIEnv* env,jobject,jobject bitmap,jint top,jint height,
    jstring frame,jstring bootstrap,jstring anchor,jstring expectedHash){
  try {
    BitmapLock lock(env,bitmap,true);
    const int w=lock.info.width;
    if(top<0||height<1||height>512||top+height>static_cast<int>(lock.info.height))throw std::runtime_error("Stripe bounds");
    const auto f=text(env,frame),b=bootstrap?text(env,bootstrap):"",a=anchor?text(env,anchor):"",expected=text(env,expectedHash);
    Bytes pixels;bool fast=false;
#ifdef RHYTHM_DAV1D
    try{pixels=dav_pixels(f,a,w,height);fast=pixel_hash(env,pixels)==expected;}
    catch(const std::exception&){if(env->ExceptionCheck())throw;}
#endif
    if(!fast){
      pixels=aom_pixels(f,b,a,w,height);
      if(pixel_hash(env,pixels)!=expected)throw std::runtime_error("Stored reconstruction mismatch");
    }
    const size_t count=static_cast<size_t>(w)*height;
    for(int y=0;y<height;y++)for(int x=0;x<w;x++){
      auto* p=lock.data+(y+top)*lock.info.stride+x*4;const auto i=y*w+x;
      p[0]=pixels[count*2+i];p[1]=pixels[i];p[2]=pixels[count+i];p[3]=255;
    }
    return fast;
  }catch(const std::exception& error){if(!env->ExceptionCheck())fail(env,error);return false;}
}
struct JpegHandle {
  tjhandle value=tj3Init(TJINIT_COMPRESS);
  JpegHandle(){if(!value)throw std::runtime_error("JPEG initialization failed");}
  ~JpegHandle(){tj3Destroy(value);}
};
extern "C" JNIEXPORT void JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_jpegEncode(JNIEnv* env,jobject,jobject bitmap,jstring path,jint quality) {
  try {
    BitmapLock lock(env,bitmap,true);
    if(quality<1||quality>100)throw std::runtime_error("JPEG quality");
    for(unsigned y=0;y<lock.info.height;y++)for(unsigned x=0;x<lock.info.width;x++)
      if(lock.data[y*lock.info.stride+x*4+3]!=255)throw std::runtime_error("Transparent images remain original");
    JpegHandle jpeg;
    tj3Set(jpeg.value,TJPARAM_SUBSAMP,TJSAMP_444);
    tj3Set(jpeg.value,TJPARAM_QUALITY,quality);
    unsigned char* output=nullptr;size_t length=0;
    const int error=tj3Compress8(jpeg.value,lock.data,lock.info.width,lock.info.stride,lock.info.height,TJPF_RGBA,&output,&length);
    std::unique_ptr<unsigned char,decltype(&tj3Free)> memory(output,tj3Free);
    if(error)throw std::runtime_error(tj3GetErrorStr(jpeg.value));
    write(text(env,path),Bytes(output,output+length));
  }catch(const std::exception& error){fail(env,error);}
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_bitmapMetrics(JNIEnv* env,jobject,jobject source,jobject decoded) {
  try {
    BitmapLock a(env,source,true),b(env,decoded,true);
    if(a.info.width!=b.info.width||a.info.height!=b.info.height)throw std::runtime_error("Image dimensions differ");
    double square=0,edge=0;size_t edgeCount=0;
    for(unsigned y=0;y<a.info.height;y++)for(unsigned x=0;x<a.info.width;x++){
      auto* p=a.data+y*a.info.stride+x*4;auto* q=b.data+y*b.info.stride+x*4;
      int gradient=0;double absSum=0;
      for(int c=0;c<3;c++){
        int d=static_cast<int>(p[c])-q[c];square+=d*d;absSum+=std::abs(d);
        if(x)gradient=std::max(gradient,std::abs(static_cast<int>(p[c])-p[c-4]));
      }
      if(gradient>32){edge+=absSum/3;edgeCount++;}
    }
    double values[2]={10*std::log10(65025/std::max(square/(a.info.width*a.info.height*3.0),1e-12)),edge/std::max<size_t>(edgeCount,1)};
    auto result=env->NewDoubleArray(2);env->SetDoubleArrayRegion(result,0,2,values);return result;
  }catch(const std::exception& error){fail(env,error);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_decodePixels(JNIEnv* env,jobject,jint w,jint h,jstring frame,jstring bootstrap,jstring anchor,jboolean directReference) {
  try {
    if(w<1||w>4096||h<1||h>512)throw std::runtime_error("Decode stripe dimensions");
#ifdef RHYTHM_DAV1D
    if(directReference){
      auto pixels=dav_pixels(text(env,frame),anchor?text(env,anchor):"",w,h);
      auto result=env->NewByteArray(pixels.size());
      if(!result)throw std::runtime_error("Pixel allocation failed");
      env->SetByteArrayRegion(result,0,pixels.size(),reinterpret_cast<jbyte*>(pixels.data()));
      return result;
    }
#endif
    std::unique_ptr<Image> reference;
    if(anchor&&!directReference)reference=load_anchor(text(env,anchor),w,h);
    Context d;decoder(d);
    // All v1 deltas disable order hints and use PRIMARY_REF_NONE. Feeding the
    // immutable keyframe directly supplies LAST without a dummy frame/copy.
    // The caller still validates the exact persisted reconstruction SHA-256.
    if(reference){decode(d,read(text(env,bootstrap)));set_reference(d,reference->im);reference.reset();}
    else if(anchor)decode(d,read(text(env,anchor)));
    auto* image=decode(d,read(text(env,frame)));
    if(image->d_w!=static_cast<unsigned>(w)||image->d_h!=static_cast<unsigned>(h)||
       image->x_chroma_shift||image->y_chroma_shift||(image->fmt&AOM_IMG_FMT_HIGHBITDEPTH))
      throw std::runtime_error("Decoded layout mismatch");
    Bytes pixels(static_cast<size_t>(w)*h*3);
    for(int p=0;p<3;p++)for(int y=0;y<h;y++)
      std::memcpy(pixels.data()+static_cast<size_t>(p*w*h+y*w),image->planes[p]+y*image->stride[p],w);
    auto result=env->NewByteArray(pixels.size());
    if(!result)throw std::runtime_error("Pixel allocation failed");
    env->SetByteArrayRegion(result,0,pixels.size(),reinterpret_cast<jbyte*>(pixels.data()));
    return result;
  }catch(const std::exception& error){fail(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_putPixels(JNIEnv* env,jobject,jobject bitmap,jbyteArray pixels,jint top,jint height) {
  try {
    BitmapLock lock(env,bitmap,true);
    if(top<0||height<1||height>512||top+height>static_cast<int>(lock.info.height))throw std::runtime_error("Stripe bounds");
    const size_t count=static_cast<size_t>(lock.info.width)*height;
    if(env->GetArrayLength(pixels)!=count*3)throw std::runtime_error("Pixel size");
    Bytes raw(count*3);env->GetByteArrayRegion(pixels,0,raw.size(),reinterpret_cast<jbyte*>(raw.data()));
    for(int y=0;y<height;y++)for(unsigned x=0;x<lock.info.width;x++){
      auto* p=lock.data+(y+top)*lock.info.stride+x*4;const auto i=y*lock.info.width+x;
      p[0]=raw[count*2+i];p[1]=raw[i];p[2]=raw[count+i];p[3]=255;
    }
  }catch(const std::exception& error){fail(env,error);}
}
#include <zlib.h>
extern "C" JNIEXPORT void JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_writePng(JNIEnv* env,jobject,jobject bitmap,jstring path) {
  try {
    BitmapLock lock(env,bitmap,true);
    const unsigned w=lock.info.width,h=lock.info.height;
    if(!w||!h||w>4096||h>8192||uint64_t(w)*h>12000000)throw std::runtime_error("PNG dimensions");
    std::ofstream out(text(env,path),std::ios::binary);
    auto be=[&](uint32_t v){unsigned char b[4]={static_cast<unsigned char>(v>>24),static_cast<unsigned char>(v>>16),static_cast<unsigned char>(v>>8),static_cast<unsigned char>(v)};out.write(reinterpret_cast<char*>(b),4);};
    auto chunk=[&](const char* type,const unsigned char* bytes,size_t n){
      be(static_cast<uint32_t>(n));out.write(type,4);
      auto crc=crc32(0,reinterpret_cast<const Bytef*>(type),4);
      if(n){out.write(reinterpret_cast<const char*>(bytes),n);crc=crc32(crc,bytes,n);}
      be(crc);
      if(!out)throw std::runtime_error("PNG write failed");
    };
    const unsigned char signature[]={137,80,78,71,13,10,26,10};
    out.write(reinterpret_cast<const char*>(signature),8);
    unsigned char header[13]={static_cast<unsigned char>(w>>24),static_cast<unsigned char>(w>>16),static_cast<unsigned char>(w>>8),static_cast<unsigned char>(w),
      static_cast<unsigned char>(h>>24),static_cast<unsigned char>(h>>16),static_cast<unsigned char>(h>>8),static_cast<unsigned char>(h),8,2,0,0,0};
    chunk("IHDR",header,13);
    // Reconstructed archives are opaque RGB. Streaming Sub-filtered rows and
    // level-1 deflate avoids another full image allocation and preserves pixels.
    struct Deflater {
      z_stream stream{};
      Deflater(){if(deflateInit(&stream,Z_BEST_SPEED)!=Z_OK)throw std::runtime_error("PNG deflate init");}
      ~Deflater(){deflateEnd(&stream);}
    } state;
    Bytes row(1+w*3), compressed(65536);
    for(unsigned y=0;y<h;y++){
      row[0]=1;
      const auto* src=lock.data+y*lock.info.stride;
      for(unsigned x=0;x<w;x++){
        if(src[x*4+3]!=255)throw std::runtime_error("PNG fast path requires opaque pixels");
        for(unsigned c=0;c<3;c++)row[1+x*3+c]=src[x*4+c]-(x?src[(x-1)*4+c]:0);
      }
      auto& s=state.stream;s.next_in=row.data();s.avail_in=row.size();
      const int flush=y+1==h?Z_FINISH:Z_NO_FLUSH;
      int status;
      do {
        s.next_out=compressed.data();s.avail_out=compressed.size();
        status=deflate(&s,flush);
        if(status!=Z_OK&&status!=Z_STREAM_END)throw std::runtime_error("PNG deflate");
        const size_t n=compressed.size()-s.avail_out;
        if(n)chunk("IDAT",compressed.data(),n);
      }while(s.avail_in||s.avail_out==0||(flush==Z_FINISH&&status!=Z_STREAM_END));
    }
    chunk("IEND",nullptr,0);
    out.flush();
    if(!out)throw std::runtime_error("PNG flush failed");
  }catch(const std::exception& error){fail(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_writeBmp(JNIEnv* env,jobject,jobject bitmap,jstring path) {
  try {
    BitmapLock lock(env,bitmap,true);
    const unsigned w=lock.info.width,h=lock.info.height;
    Bytes header(54,0);
    auto u32=[&](int at,unsigned value){for(int i=0;i<4;i++)header[at+i]=(value>>(i*8))&255;};
    header[0]='B';header[1]='M';u32(2,54+w*h*4);u32(10,54);u32(14,40);u32(18,w);u32(22,h);
    header[26]=1;header[28]=32;u32(34,w*h*4);
    std::ofstream out(text(env,path),std::ios::binary);
    out.write(reinterpret_cast<char*>(header.data()),header.size());
    Bytes row(w*4);
    for(int y=h-1;y>=0;y--){
      auto* p=lock.data+y*lock.info.stride;
      for(unsigned x=0;x<w;x++){
        row[x*4]=p[x*4+2];row[x*4+1]=p[x*4+1];row[x*4+2]=p[x*4];row[x*4+3]=255;
      }
      out.write(reinterpret_cast<char*>(row.data()),row.size());
    }
    if(!out)throw std::runtime_error("BMP write failed");
  }catch(const std::exception& error){fail(env,error);}
}
extern "C" JNIEXPORT jint JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_run(JNIEnv* env,jobject,jobjectArray args) {
  try {
    std::vector<std::string> values{"rhythm"};
    for(int i=0;i<env->GetArrayLength(args);++i){
      auto item=static_cast<jstring>(env->GetObjectArrayElement(args,i));
      values.push_back(text(env,item));env->DeleteLocalRef(item);
    }
    std::vector<char*> argv;for(auto& value:values)argv.push_back(value.data());
    return rhythm_probe_main(static_cast<int>(argv.size()),argv.data());
  }catch(const std::exception& error){fail(env,error);return 1;}
}
extern "C" JNIEXPORT void JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_writeStripe(JNIEnv* env,jobject,jobject bitmap,jstring path) {
  try {
    BitmapLock lock(env,bitmap);
    std::ofstream out(text(env,path),std::ios::binary);
    std::vector<unsigned char> row(lock.info.width);
    for(int channel:{1,2,0})for(unsigned y=0;y<lock.info.height;++y){
      for(unsigned x=0;x<lock.info.width;++x){
        if(lock.data[y*lock.info.stride+x*4+3]!=255)throw std::runtime_error("Transparent images remain original");
        row[x]=lock.data[y*lock.info.stride+x*4+channel];
      }
      out.write(reinterpret_cast<char*>(row.data()),row.size());
    }
    if(!out)throw std::runtime_error("Stripe write");
  }catch(const std::exception& error){fail(env,error);}
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_metrics(JNIEnv* env,jobject,jobject bitmap,jstring path) {
  try {
    BitmapLock lock(env,bitmap);auto raw=read(text(env,path));
    const size_t count=static_cast<size_t>(lock.info.width)*lock.info.height;
    if(raw.size()!=count*3)throw std::runtime_error("Reconstruction length");
    double square=0,edge=0;size_t edgeCount=0;
    const int planes[3]={2,0,1};
    for(unsigned y=0;y<lock.info.height;++y)for(unsigned x=0;x<lock.info.width;++x){
      auto* p=lock.data+y*lock.info.stride+x*4;double absSum=0;int gradient=0;
      for(int c=0;c<3;++c){
        const int d=static_cast<int>(p[c])-raw[planes[c]*count+y*lock.info.width+x];
        square+=d*d;absSum+=std::abs(d);
        if(x)gradient=std::max(gradient,std::abs(static_cast<int>(p[c])-p[c-4]));
      }
      if(gradient>32){edge+=absSum/3;edgeCount++;}
    }
    const double result[2]={10*std::log10(65025/std::max(square/(count*3),1e-12)),
                            edge/std::max<size_t>(edgeCount,1)};
    auto array=env->NewDoubleArray(2);env->SetDoubleArrayRegion(array,0,2,result);return array;
  }catch(const std::exception& error){fail(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_deckers_thibault_aves_rhythm_RhythmNative_readStripe(JNIEnv* env,jobject,jobject bitmap,jstring path) {
  try {
    BitmapLock lock(env,bitmap);auto raw=read(text(env,path));
    const size_t count=static_cast<size_t>(lock.info.width)*lock.info.height;
    if(raw.size()!=count*3)throw std::runtime_error("Reconstruction length");
    for(unsigned y=0;y<lock.info.height;++y)for(unsigned x=0;x<lock.info.width;++x){
      auto* p=lock.data+y*lock.info.stride+x*4;const auto i=y*lock.info.width+x;
      p[0]=raw[count*2+i];p[1]=raw[i];p[2]=raw[count+i];p[3]=255;
    }
  }catch(const std::exception& error){fail(env,error);}
}
