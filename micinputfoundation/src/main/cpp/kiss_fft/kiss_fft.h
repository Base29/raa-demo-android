#ifndef KISS_FFT_H
#define KISS_FFT_H

#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <string.h>

#ifdef __cplusplus
extern "C"
{
#endif

    /*
     ATTENTION!
     If you would like a :
     -- a fixed point version of kiss fft
     -- a mixed endian version of kiss fft
     -- a global-variable system that allows multiple threads to overlap
     -- a small-memory version for 16 bit micros
     -- other customizations

     Then check out the full distribution of kiss fft.

     This header only provides the basic complex FFT functionalities.
    */

#ifdef FIXED_POINT
#include <sys/types.h>
#if (FIXED_POINT == 32)
#define kiss_fft_scalar int32_t
#else
#define kiss_fft_scalar int16_t
#endif
#else
#ifndef kiss_fft_scalar
/*  default is float */
#define kiss_fft_scalar float
#endif
#endif

    typedef struct
    {
        kiss_fft_scalar r;
        kiss_fft_scalar i;
    } kiss_fft_cpx;

    typedef struct kiss_fft_state *kiss_fft_cfg;

    /*
     *  kiss_fft_alloc
     *
     *  Initialize a FFT (or IFFT) planned timestep with nfft size.
     *  If inverse_fft is non-zero, then the output will be inverse fft.
     *
     *  Returns a configuration structure that can be used later.
     *  (This structure must be freed with free()).
     */
    kiss_fft_cfg kiss_fft_alloc(int nfft, int inverse_fft, void *mem, size_t *lenmem);

    /*
     * kiss_fft
     *
     * Performs an FFT on "fin" and stores the result in "fout".
     * The cfg object is required and must have been created with kiss_fft_alloc.
     *
     * "fin" is an array of nfft complex input points.
     * "fout" is an array of nfft complex output points.
     */
    void kiss_fft(kiss_fft_cfg cfg, const kiss_fft_cpx *fin, kiss_fft_cpx *fout);

    /*
     * kiss_fft_stride
     *
     * Performs an FFT on "fin" and stores the result in "fout".
     * The cfg object is required and must have been created with kiss_fft_alloc.
     *
     * "fin" is an array of nfft complex input points.
     * "fout" is an array of nfft complex output points.
     * "fin_stride" is the stride of the input array.
     */
    void kiss_fft_stride(kiss_fft_cfg cfg, const kiss_fft_cpx *fin, kiss_fft_cpx *fout, int fin_stride);

    /*
     * kiss_fft_cleanup
     *
     * Frees any memory allocated by kiss_fft_alloc needed for the configuration structure.
     */
    void kiss_fft_cleanup(void);

    /*
     * kiss_fft_next_fast_size
     *
     * Returns the smallest integer k, such that k>=n and k has only prime factors 2,3,5.
     * This is the "next fast size" for the FFT.
     */
    int kiss_fft_next_fast_size(int n);

#define kiss_fft_free free

#ifdef __cplusplus
}
#endif

#endif
