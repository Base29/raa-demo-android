#ifndef KISS_FFTR_H
#define KISS_FFTR_H

#include "kiss_fft.h"
#ifdef __cplusplus
extern "C" {
#endif

/*

 Real optimized version of kiss_fft

 Usage:

 kiss_fftr_cfg st = kiss_fftr_alloc(1024,0,NULL,NULL);
 kiss_fftr(st,timedata,freqdata);

 */

typedef struct kiss_fftr_state *kiss_fftr_cfg;

kiss_fftr_cfg kiss_fftr_alloc(int nfft, int inverse_fft, void *mem,
                              size_t *lenmem);
/*
 nfft must be even

 If you don't care to allocate success -
 one large chunk of mem, specify lenmem=NULL
 */

void kiss_fftr(kiss_fftr_cfg cfg, const kiss_fft_scalar *timedata,
               kiss_fft_cpx *freqdata);
/*
 input timedata has nfft scalar points
 output freqdata has nfft/2+1 complex points
*/

void kiss_fftri(kiss_fftr_cfg cfg, const kiss_fft_cpx *freqdata,
                kiss_fft_scalar *timedata);
/*
 input freqdata has  nfft/2+1 complex points
 output timedata has nfft scalar points
*/

#define kiss_fftr_free free

#ifdef __cplusplus
}
#endif
#endif
