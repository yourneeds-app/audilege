#include "ap2_ptp_shm.h"
bool ap2_ptp_shm_writer_open(struct ap2_ptp_shm_writer *w){return false;}
void ap2_ptp_shm_publish(struct ap2_ptp_shm_writer *w,struct ap2_ptp_shm_sample s){}
void ap2_ptp_shm_writer_close(struct ap2_ptp_shm_writer *w){}
bool ap2_ptp_shm_reader_open(struct ap2_ptp_shm_reader *r){return false;}
bool ap2_ptp_shm_read(struct ap2_ptp_shm_reader *r,struct ap2_ptp_shm_sample *s){return false;}
void ap2_ptp_shm_reader_close(struct ap2_ptp_shm_reader *r){}
bool ap2_ptp_ctrl_send(const char *cmd,int timeout_ms,char *ack,int ack_len){return false;}
int ap2_ptp_ctrl_server_open(void){return -1;}
