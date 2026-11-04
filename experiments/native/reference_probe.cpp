// Research-only single-reference AV1 experiment. No Android integration.
#include <aom/aom_encoder.h>
#include <aom/aom_decoder.h>
#include <aom/aomcx.h>
#include <aom/aomdx.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using Bytes = std::vector<unsigned char>;
using Clock = std::chrono::steady_clock;
#ifndef RHYTHM_CODEC_THREADS
#ifdef __ANDROID__
#define RHYTHM_CODEC_THREADS 2
#else
#define RHYTHM_CODEC_THREADS 1
#endif
#endif
static double ms(Clock::time_point t) {
  return std::chrono::duration<double, std::milli>(Clock::now()-t).count();
}
static void check(aom_codec_err_t error, aom_codec_ctx_t* context, const char* operation) {
  if (error) throw std::runtime_error(std::string(operation)+": "+
      aom_codec_err_to_string(error)+" "+
      (aom_codec_error_detail(context) ? aom_codec_error_detail(context) : ""));
}
static Bytes read(const std::string& path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f) throw std::runtime_error("Cannot open input: "+path);
  auto length=f.tellg();
  if (length<0 || length>128*1024*1024) throw std::runtime_error("Input size limit");
  Bytes result(static_cast<size_t>(length)); f.seekg(0);
  if (!f.read(reinterpret_cast<char*>(result.data()), length)) throw std::runtime_error("Read failed");
  return result;
}
static void write(const std::string& path, const Bytes& bytes) {
  std::ifstream exists(path);
  if (exists.good()) throw std::runtime_error("Refusing to overwrite "+path);
  std::ofstream f(path, std::ios::binary);
  f.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
  if (!f) throw std::runtime_error("Write failed");
}
struct Context {
  aom_codec_ctx_t c{};
  bool live=false;
  ~Context(){ if(live) aom_codec_destroy(&c); }
};
struct Image {
  aom_image_t* im;
  Image(int w,int h):im(aom_img_alloc(nullptr,AOM_IMG_FMT_I444,w,h,32)){
    if(!im) throw std::runtime_error("Image allocation failed");
    im->cp=AOM_CICP_CP_BT_709; im->tc=AOM_CICP_TC_SRGB;
    im->mc=AOM_CICP_MC_IDENTITY; im->range=AOM_CR_FULL_RANGE;
  }
  ~Image(){aom_img_free(im);}
};
static void decoder(Context& d) {
  aom_codec_dec_cfg_t config{}; config.threads=1; config.allow_lowbitdepth=1;
  check(aom_codec_dec_init(&d.c,aom_codec_av1_dx(),&config,0),&d.c,"decoder init");
  d.live=true;
}
static aom_image_t* decode(Context& d,const Bytes& data) {
  check(aom_codec_decode(&d.c,data.data(),data.size(),nullptr),&d.c,"decode");
  aom_codec_iter_t iter=nullptr;
  auto* image=aom_codec_get_frame(&d.c,&iter);
  if(!image) throw std::runtime_error("No decoded image");
  return image;
}
static void set_reference(Context& context,const aom_image_t* image) {
  av1_ref_frame_t ref{}; ref.idx=0; ref.img=*image; ref.use_external_ref=0;
  check(aom_codec_control(&context.c,AV1_SET_REFERENCE,&ref),&context.c,"set reference");
}
static void write_pixels(const std::string& path,const aom_image_t* image,int w,int h){
  if(image->d_w!=static_cast<unsigned>(w)||image->d_h!=static_cast<unsigned>(h)||
     image->x_chroma_shift||image->y_chroma_shift||
     (image->fmt&AOM_IMG_FMT_HIGHBITDEPTH))
    throw std::runtime_error("Expected 8-bit full-size planar image");
  std::ifstream exists(path);
  if(exists.good())throw std::runtime_error("Refusing to overwrite "+path);
  std::ofstream output(path,std::ios::binary);
  if(!output)throw std::runtime_error("Cannot open pixel output: "+path+" errno="+std::to_string(errno));
  for(int p=0;p<3;p++)for(int y=0;y<h;y++)
    output.write(reinterpret_cast<const char*>(image->planes[p]+y*image->stride[p]),w);
  if(!output)throw std::runtime_error("Pixel write failed: "+path+" errno="+std::to_string(errno));
}
static std::unique_ptr<Image> load_anchor(const std::string& path,int w,int h){
  Context ref;decoder(ref);auto* source=decode(ref,read(path));
  if(source->d_w!=static_cast<unsigned>(w)||source->d_h!=static_cast<unsigned>(h)||
     source->x_chroma_shift||source->y_chroma_shift||
     (source->fmt&AOM_IMG_FMT_HIGHBITDEPTH))
    throw std::runtime_error("Unexpected anchor layout");
  auto result=std::make_unique<Image>(w,h);
  for(int p=0;p<3;p++)for(int y=0;y<h;y++)
    std::memcpy(result->im->planes[p]+y*result->im->stride[p],
                source->planes[p]+y*source->stride[p],w);
  return result; // Decoder is destroyed before target codec initialization.
}
static Bytes encode(Context& e,aom_image_t* image,int number,aom_enc_frame_flags_t flags,bool* key=nullptr) {
  check(aom_codec_encode(&e.c,image,number,1,flags),&e.c,"encode");
  aom_codec_iter_t iter=nullptr; const aom_codec_cx_pkt_t* packet;
  Bytes bytes; int frames=0;
  while((packet=aom_codec_get_cx_data(&e.c,&iter)))if(packet->kind==AOM_CODEC_CX_FRAME_PKT){
    frames++;
    auto* start=static_cast<const unsigned char*>(packet->data.frame.buf);
    bytes.insert(bytes.end(),start,start+packet->data.frame.sz);
    if(key)*key=(packet->data.frame.flags&AOM_FRAME_IS_KEY)!=0;
  }
  if(frames!=1)throw std::runtime_error("Expected exactly one immediate output frame");
  return bytes;
}
int main(int argc,char** argv) try {
  const auto total_start=Clock::now();
  if(argc<4)throw std::runtime_error("encode w h q speed planar-gbr anchor-obu-or-dash prefix | decode w h bootstrap-or-dash anchor-or-dash frame output");
  std::string mode=argv[1]; int w=std::stoi(argv[2]),h=std::stoi(argv[3]);
  if(w<1||h<1||static_cast<int64_t>(w)*h>20000000)throw std::runtime_error("Dimension limit");
  if(mode=="decode"){
    if(argc!=9)throw std::runtime_error("decode args");
    std::unique_ptr<Image> reference;
    if(std::string(argv[5])!="-"){
      reference=load_anchor(argv[5],w,h);
    }
    Context d; decoder(d);
    if(reference){
      decode(d,read(argv[4])); set_reference(d,reference->im);reference.reset();
    }
    auto* result=decode(d,read(argv[6]));
    write_pixels(argv[7],result,w,h);
    // argv[8] is a run label, intentionally not interpreted as a path.
    std::cout<<"{\"decode_ms\":"<<ms(total_start)<<"}\n"; return 0;
  }
  if(mode!="encode"||(argc!=9 && argc!=10))throw std::runtime_error("encode args");
  const int threads=argc==10?std::stoi(argv[9]):RHYTHM_CODEC_THREADS;
  if(threads<1||threads>4)throw std::runtime_error("Thread limit");
  int q=std::stoi(argv[4]),speed=std::stoi(argv[5]);
  if(q<0||q>63||speed<6||speed>12)throw std::runtime_error("q/speed outside probe limits");
  std::string anchor_path=argv[7],prefix=argv[8];
  bool delta=anchor_path!="-";
  double anchor_decode_ms=0;
  std::unique_ptr<Image> reference;
  if(delta){auto t=Clock::now();reference=load_anchor(anchor_path,w,h);anchor_decode_ms=ms(t);}
  Context e; aom_codec_enc_cfg_t cfg{};
  check(aom_codec_enc_config_default(aom_codec_av1_cx(),&cfg,AOM_USAGE_REALTIME),&e.c,"config");
  // Bound mobile CPU use; the desktop two-thread target checks the same path.
  cfg.g_w=w;cfg.g_h=h;cfg.g_profile=1;cfg.g_threads=threads;cfg.g_lag_in_frames=0;
  cfg.g_timebase.num=1;cfg.g_timebase.den=1;
  cfg.rc_end_usage=AOM_Q;cfg.rc_min_quantizer=q;cfg.rc_max_quantizer=q;
  cfg.kf_mode=AOM_KF_DISABLED;cfg.kf_min_dist=0;cfg.kf_max_dist=9999;
  cfg.g_error_resilient=1;
  check(aom_codec_enc_init(&e.c,aom_codec_av1_cx(),&cfg,0),&e.c,"encoder init");e.live=true;
#define CONTROL(id,value) check(aom_codec_control(&e.c,id,value),&e.c,#id)
  CONTROL(AOME_SET_CPUUSED,speed);CONTROL(AOME_SET_CQ_LEVEL,q);
  CONTROL(AV1E_SET_ROW_MT,cfg.g_threads>1?1u:0u);CONTROL(AOME_SET_ENABLEAUTOALTREF,0u);
  CONTROL(AV1E_SET_ENABLE_ORDER_HINT,0);
  CONTROL(AV1E_SET_TUNE_CONTENT,AOM_CONTENT_DEFAULT);
  CONTROL(AV1E_SET_COLOR_PRIMARIES,AOM_CICP_CP_BT_709);
  CONTROL(AV1E_SET_TRANSFER_CHARACTERISTICS,AOM_CICP_TC_SRGB);
  CONTROL(AV1E_SET_MATRIX_COEFFICIENTS,AOM_CICP_MC_IDENTITY);
  CONTROL(AV1E_SET_COLOR_RANGE,AOM_CR_FULL_RANGE);
  Image input(w,h);
  Bytes bootstrap;
  double bootstrap_ms=0;
  if(delta){
    for(int p=0;p<3;p++)for(int y=0;y<h;y++)std::memset(input.im->planes[p]+y*input.im->stride[p],128,w);
    auto t=Clock::now();bootstrap=encode(e,input.im,0,AOM_EFLAG_FORCE_KF);bootstrap_ms=ms(t);
    set_reference(e,reference->im);
    reference.reset();
  }
  {
    std::ifstream raw(argv[6],std::ios::binary|std::ios::ate);
    if(!raw||raw.tellg()!=static_cast<int64_t>(w)*h*3)throw std::runtime_error("Input pixel length mismatch");
    raw.seekg(0);
    for(int p=0;p<3;p++)for(int y=0;y<h;y++)
      if(!raw.read(reinterpret_cast<char*>(input.im->planes[p]+y*input.im->stride[p]),w))
        throw std::runtime_error("Pixel read failed");
  }
  bool key=false;auto t=Clock::now();
  auto flags=delta?(AOM_EFLAG_ERROR_RESILIENT|AOM_EFLAG_SET_PRIMARY_REF_NONE|
                   AOM_EFLAG_NO_UPD_ENTROPY|AOM_EFLAG_NO_REF_LAST2|AOM_EFLAG_NO_REF_LAST3|
                   AOM_EFLAG_NO_REF_GF|AOM_EFLAG_NO_REF_ARF|AOM_EFLAG_NO_REF_BWD|
                   AOM_EFLAG_NO_REF_ARF2):AOM_EFLAG_FORCE_KF;
  Bytes frame=encode(e,input.im,delta?1:0,flags,&key);
  double encode_ms=ms(t);
  if(delta&&key)throw std::runtime_error("Delta was unexpectedly encoded as a key frame");
  aom_image_t reconstructed{};
  check(aom_codec_control(&e.c,AV1_GET_NEW_FRAME_IMAGE,&reconstructed),&e.c,"encoder reconstruction");
  write_pixels(prefix+".encoder.gbr",&reconstructed,w,h);
  write(prefix+".obu",frame);
  if(delta)write(prefix+".bootstrap.obu",bootstrap);
  std::cout<<"{\"bytes\":"<<frame.size()<<",\"bootstrap_bytes\":"<<bootstrap.size()
    <<",\"keyframe\":"<<(key?"true":"false")<<",\"encode_ms\":"<<encode_ms
    <<",\"bootstrap_ms\":"<<bootstrap_ms<<",\"anchor_decode_ms\":"<<anchor_decode_ms
    <<",\"total_ms\":"<<ms(total_start)<<",\"threads\":"<<cfg.g_threads<<"}\n";
  return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
