package main
/*
#include <stdlib.h>
*/
import "C"
import "unsafe"
//export bc_invoke
func bc_invoke(data *C.char,length C.int)*C.char{return C.CString(string(invoke(C.GoBytes(unsafe.Pointer(data),length))))}
