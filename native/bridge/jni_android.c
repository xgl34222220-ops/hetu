#include <jni.h>
#include <stdlib.h>
#include <string.h>
extern char *bc_invoke(char*,int);
static jbyteArray call(JNIEnv *env,jbyteArray bytes){
 if(!bytes)return NULL;
 jsize n=(*env)->GetArrayLength(env,bytes);if(n<0||n>40*1024*1024)return NULL;
 char *input=malloc((size_t)n+1);if(!input)return NULL;
 (*env)->GetByteArrayRegion(env,bytes,0,n,(jbyte*)input);if((*env)->ExceptionCheck(env)){free(input);return NULL;}
 input[n]=0;char *result=bc_invoke(input,n);free(input);if(!result)return NULL;
 jsize size=(jsize)strlen(result);jbyteArray out=(*env)->NewByteArray(env,size);
 if(out)(*env)->SetByteArrayRegion(env,out,0,size,(jbyte*)result);free(result);return out;
}
JNIEXPORT jbyteArray JNICALL Java_io_github_xgl34222220_bichen_MihomoNative_invoke(JNIEnv *e,jclass c,jbyteArray b){return call(e,b);}
JNIEXPORT jbyteArray JNICALL Java_io_github_xgl34222220_bichen_preview_MihomoNative_invoke(JNIEnv *e,jclass c,jbyteArray b){return call(e,b);}
